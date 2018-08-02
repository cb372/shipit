import cats.{Monoid, Semigroup}
import ciris._
import ciris.aws.ssm._
import com.amazonaws.util.EC2MetadataUtils
import cats.syntax.either._
import cats.instances.either._
import cats.instances.parallel._
import cats.syntax.parallel._

import scala.util.Try

case class ESConfig(
    endpointUrl: String
)

object ESConfig {

  def load(): Either[ConfigErrors, ESConfig] = {
    loadConfig(param[String]("shipit.es.endpointUrl"))(ESConfig.apply)
  }

}

case class SlackConfig(
    webhookUrl: String
)

object SlackConfig {

  def load(): Either[ConfigErrors, SlackConfig] = {
    loadConfig(param[String]("shipit.slack.webhookUrl"))(SlackConfig.apply)
  }

}

case class JiraConfig(
    username: String,
    password: String
)

object JiraConfig {

  def load(): Either[ConfigErrors, JiraConfig] = {
    loadConfig(
      param[String]("shipit.jira.username"),
      param[String]("shipit.jira.password")
    )(JiraConfig.apply)
  }

}

object GoogleConfig {

  def load(localhost: Boolean): Either[ConfigErrors, GoogleConfig] = {
    if (localhost) {
      // These credentials will only work when running the app on localhost:9000, i.e. on a developer machine
      GoogleConfig(
        clientId = "925213464964-ppl7hcrpsflavpf8rtlp2mnjm5p0pi0t.apps.googleusercontent.com",
        clientSecret = "I9yZu57pLe4VklD7mJuQZyel",
        redirectUrl = "http://localhost:9000/oauth2callback"
      ).asRight
    } else {
      loadConfig(
        param[String]("shipit.google.clientId"),
        param[String]("shipit.google.clientSecret")
      )(GoogleConfig.apply(_, _, redirectUrl = "https://shipit.ovotech.org.uk/oauth2callback"))
    }
  }

}

case class GoogleConfig(
    clientId: String,
    clientSecret: String,
    redirectUrl: String
)

case class AdminConfig(
    adminEmailAddresses: List[String]
)

object AdminConfig {

  def load(): Either[ConfigErrors, AdminConfig] = {
    AdminConfig(adminEmailAddresses = List("chris.birchall@ovoenergy.com")).asRight
  }

}

case class Config(
    es: ESConfig,
    slack: SlackConfig,
    jira: JiraConfig,
    google: GoogleConfig,
    admin: AdminConfig
)

object Config {

  def unsafeLoad(): Config = {
    load() match {
      case Left(errors) => throw errors.toException // KABOOM!
      case Right(c)     => c
    }
  }

  private val runningInAWS: Boolean =
    Try(EC2MetadataUtils.getInstanceId() != null).getOrElse(false)

  def load(): Either[ConfigErrors, Config] = {
    (
      ESConfig.load(),
      SlackConfig.load(),
      JiraConfig.load(),
      GoogleConfig.load(runningInAWS),
      AdminConfig.load()
    ).parMapN(Config.apply)
  }

  private implicit val configErrorsSemigroup: Semigroup[ConfigErrors] = new Semigroup[ConfigErrors] {
    override def combine(x: ConfigErrors, y: ConfigErrors): ConfigErrors =
      ConfigErrors(x.toVector.head, x.toVector.tail ++ y.toVector: _*)
  }

}
