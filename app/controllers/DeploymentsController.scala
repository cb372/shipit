package controllers

import java.time.OffsetDateTime

import com.gu.googleauth.{AuthAction, GoogleAuthConfig, UserIdentity}
import elasticsearch.Elastic55
import logic.Deployments
import models.Link
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.ws.WSClient
import play.api.mvc._

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

  val jestClient = ctx.jestClient

  val healthcheck = Action { Ok("OK") }

  val index = authAction { request =>
    implicit val user: UserIdentity = request.user
    Ok(views.html.index())
  }

  def search(team: Option[String], service: Option[String], buildId: Option[String], page: Int) = authAction {
    implicit request =>
      implicit val user: UserIdentity = request.user
      val showAdminColumn             = ctx.isAdmin(user)
      val (teamQuery, serviceQuery, buildIdQuery) =
        (team.filter(_.nonEmpty), service.filter(_.nonEmpty), buildId.filter(_.nonEmpty))
      val searchResult = Elastic55.Deployments.search(teamQuery, serviceQuery, buildIdQuery, page).run(jestClient)
      Ok(views.html.deployments.search(searchResult, teamQuery, serviceQuery, buildIdQuery, showAdminColumn))
  }

  def create = apiKeyAuth.ApiKeyAuthAction.async { implicit request =>
    DeploymentForm.bindFromRequest.fold(
      _ =>
        Future.successful(
          BadRequest(
            """You must include at least the following form fields in your POST: 'team', 'service', 'buildId'.
            |You may also include the following fields:
            |- one or more links (e.g. links[0].title=PR, links[0].url=http://github.com/my-pr) (link title and URL must both be non-empty strings)
            |- a 'note' field containing any notes about the deployment (can be an empty string)
            |- a 'notifySlackChannel' field containing an additional Slack channel that you want to notify (#announce_change will always be notified)
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
            data.links.getOrElse(Nil),
            data.note,
            data.notifySlackChannel
          )
          .run(ctx)
          .map(_ => Ok("ok"))
      }
    )
  }

  def delete(id: String) = authAction { request =>
    implicit val user: UserIdentity = request.user
    if (ctx.isAdmin(user)) {
      Elastic55.Deployments.delete(id).run(ctx.jestClient) match {
        case Left(errorMessage) => Ok(s"Failed to delete $id. Error message: $errorMessage")
        case Right(_)           => Ok(s"Deleted $id")
      }
    } else
      Forbidden("Sorry, you're not cool enough")
  }

}

object DeploymentsController {

  case class DeploymentFormData(
      team: String,
      service: String,
      buildId: String,
      links: Option[List[Link]],
      note: Option[String],
      notifySlackChannel: Option[String]
  )

  val DeploymentForm = Form(
    mapping(
      "team"    -> nonEmptyText,
      "service" -> nonEmptyText,
      "buildId" -> nonEmptyText,
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
