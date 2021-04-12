import akka.actor.ActorSystem
import cats.arrow.FunctionK
import cats.effect.{ContextShift, IO, Timer}
import cats.~>
import com.gu.googleauth.{AntiForgeryChecker, AuthAction, GoogleAuthConfig, UserIdentity}
import com.gu.play.secretrotation.DualSecretTransition.InitialSecret
import com.sksamuel.elastic4s.cats.effect.instances.CatsEffectInstances
import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import controllers._
import datadog.Datadog
import elasticsearch.AWSClient
import logic.Deployments
import play.api.ApplicationLoader.Context
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{AnyContent, EssentialFilter}
import play.api.routing.Router
import play.api.{BuiltInComponentsFromContext, Configuration}
import cats.tagless.syntax.functorK._
import play.filters.HttpFiltersComponents
import play.filters.csrf.CSRFComponents
import router.Routes
import slack.Slack

import scala.concurrent.Future

class AppComponents(context: Context, config: Config)
    extends BuiltInComponentsFromContext(context)
    with AhcWSComponents
    with CSRFComponents
    with HttpFiltersComponents
    with AssetsComponents
    with CatsEffectInstances {

  implicit val actorSys: ActorSystem = actorSystem

  override def configuration: Configuration =
    context.initialConfiguration ++ Configuration("play.http.secret.key" -> config.play.secretKey.value)

  val googleAuthConfig: GoogleAuthConfig =
    GoogleAuthConfig(
      clientId = config.google.clientId,
      clientSecret = config.google.clientSecret.value,
      redirectUrl = config.google.redirectUrl,
      domains = List(
        "sseenergyservices.com",
        "ovoenergy.com",
        "kaluza.com"
      ),
      antiForgeryChecker = AntiForgeryChecker(
        InitialSecret(httpConfiguration.secret.secret),
        AntiForgeryChecker.signatureAlgorithmFromPlay(httpConfiguration)
      )
    )

  private implicit val cs: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.global)

  private implicit val timer: Timer[IO] =
    IO.timer(scala.concurrent.ExecutionContext.global)

  val elasticClient: ElasticClient =
    ElasticClient(AWSClient.create(ElasticProperties(config.es.endpointUrl)))

  val slackCtx: Slack.Context =
    Slack.Context(wsClient, config.slack.webhookUrl.value)

  val datadogCtx: Datadog.Context =
    Datadog.Context(wsClient, config.datadog.apiKey.value)

  val isAdmin: UserIdentity => Boolean =
    (user: UserIdentity) => config.admin.adminEmailAddresses.contains(user.email)

  private def toFuture[A](io: IO[A]): Future[A] =
    io.unsafeToFuture

  val toFutureK: IO ~> Future =
    FunctionK.lift(toFuture)

  val depls: deployments.Deployments[Future] =
    deployments.Deployments[IO](elasticClient).mapK(toFutureK)

  val keys: apikeys.ApiKeys[Future] =
    apikeys.ApiKeys[IO](elasticClient).mapK(toFutureK)

  val deploymentsCtx: Deployments.Context =
    Deployments.Context(slackCtx, datadogCtx, depls, isAdmin)

  val authAction: AuthAction[AnyContent] =
    new AuthAction(
      authConfig = googleAuthConfig,
      loginTarget = routes.AuthController.login(),
      bodyParser = controllerComponents.parsers.default
    )(executionContext)

  val apiKeyAuth: ApiKeyAuth =
    new ApiKeyAuth(keys, defaultActionBuilder)

  val mainController: MainController =
    new MainController(controllerComponents, authAction, googleAuthConfig, wsClient)

  val apiKeysController: ApiKeysController =
    new ApiKeysController(controllerComponents, authAction, googleAuthConfig, wsClient, keys)

  val deploymentsController: DeploymentsController =
    new DeploymentsController(controllerComponents, authAction, apiKeyAuth, googleAuthConfig, wsClient, deploymentsCtx)

  val servicesController: ServicesController =
    new ServicesController(controllerComponents, authAction, googleAuthConfig, wsClient, depls)

  val authController: AuthController =
    new AuthController(controllerComponents, googleAuthConfig, wsClient)

  lazy val router: Router = new Routes(
    httpErrorHandler,
    mainController,
    deploymentsController,
    servicesController,
    apiKeysController,
    authController,
    assets
  )

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(csrfFilter)

}
