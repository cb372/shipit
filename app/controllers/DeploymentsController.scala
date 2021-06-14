package controllers

import com.gu.googleauth.{AuthAction, GoogleAuthConfig, UserIdentity}
import deployments.Environment.Prod
import deployments.{Environment, Link, SearchTerms}
import logic.Deployments
import play.api.data.{Form, Mapping, validation}
import play.api.data.Forms._
import play.api.data.validation.Constraint
import play.api.libs.ws.WSClient
import play.api.mvc._

import java.time.OffsetDateTime
import scala.concurrent.{ExecutionContext, Future}

class DeploymentsController(
    controllerComponents: ControllerComponents,
    authAction: AuthAction[AnyContent],
    apiKeyAuth: ApiKeyAuth,
    val authConfig: GoogleAuthConfig,
    val wsClient: WSClient,
    ctx: Deployments.Context
)(implicit val ec: ExecutionContext)
    extends AbstractController(controllerComponents) {

  import DeploymentsController._

  val healthcheck: Action[AnyContent] =
    Action { Ok("OK") }

  val index: Action[AnyContent] =
    authAction { request =>
      implicit val user: UserIdentity = request.user
      Ok(views.html.index())
    }

  def search(terms: SearchTerms, page: Int): Action[AnyContent] =
    authAction.async { implicit request =>
      implicit val user: UserIdentity = request.user
      val showAdminColumn             = ctx.isAdmin(user)
      ctx.deployments.search(terms, page).map { result =>
        Ok(views.html.deployments.search(result, terms, showAdminColumn))
      }
    }

  def create: Action[AnyContent] =
    apiKeyAuth.ApiKeyAuthAction.async { implicit request =>
      DeploymentForm
        .bindFromRequest()
        .fold(
          _ =>
            Future.successful(
              BadRequest(
                """You must include at least the following form fields in your POST: 'team', 'service', 'buildId'.
                |You may also include the following fields:
                |- one or more links (e.g. links[0].title=PR, links[0].url=http://github.com/my-pr) (link title and URL must both be non-empty strings)
                |- a 'note' field containing any notes about the deployment (can be an empty string)
                |- a 'notifySlackChannel' field containing an additional Slack channel that you want to notify (#announce_change will always be notified of prod deploys)
                |- an 'environment' field set to either 'nonprod' / 'uat' or 'prod' / 'prd'. #announce_change will not be notified of nonprod deployments.
                |""".stripMargin
              )
            ),
          data => {
            Deployments
              .createDeployment(
                data.team,
                data.service,
                data.buildId,
                OffsetDateTime.now(),
                data.environment.getOrElse(Prod),
                data.links.getOrElse(Nil),
                data.note,
                data.notifySlackChannel
              )
              .run(ctx)
              .map(_ => Ok("ok"))
          }
        )
    }

  def delete(id: String): Action[AnyContent] =
    authAction.async { request =>
      implicit val user: UserIdentity = request.user
      if (ctx.isAdmin(user)) {
        ctx.deployments.delete(id).map {
          case Left(errorMessage) => Ok(s"Failed to delete $id. Error message: $errorMessage")
          case Right(_)           => Ok(s"Deleted $id")
        }
      } else Future.successful(Forbidden("Sorry, you're not cool enough"))
    }
}

object DeploymentsController {

  case class DeploymentFormData(
      team: String,
      service: String,
      buildId: String,
      environment: Option[Environment],
      links: Option[List[Link]],
      note: Option[String],
      notifySlackChannel: Option[String]
  )

  val environment: Mapping[Environment] =
    nonEmptyText
      .verifying(
        Constraint { str: String =>
          Environment.fromString(str) match {
            case Left(value) => validation.Invalid(value)
            case Right(_)    => validation.Valid
          }
        }
      )
      .transform(
        Environment.fromString(_).toOption.get,
        _.name
      )

  val DeploymentForm: Form[DeploymentFormData] = Form(
    mapping(
      "team"        -> nonEmptyText,
      "service"     -> nonEmptyText,
      "buildId"     -> nonEmptyText,
      "environment" -> optional(environment),
      "links" -> optional(
        list(
          mapping(
            "title" -> nonEmptyText,
            "url"   -> nonEmptyText
          )(Link.apply)(Link.unapply)
        )
      ),
      "note"               -> optional(text),
      "notifySlackChannel" -> optional(nonEmptyText)
    )(DeploymentFormData.apply)(DeploymentFormData.unapply)
  )

}
