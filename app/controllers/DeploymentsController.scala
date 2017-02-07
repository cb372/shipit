package controllers

import java.time.OffsetDateTime

import com.gu.googleauth.GoogleAuthConfig
import es.ES
import io.searchbox.client.JestClient
import logic.Deployments
import models.DeploymentResult.Succeeded
import models.{DeploymentResult, Link}
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.ws.WSClient
import play.api.mvc._

class DeploymentsController(val authConfig: GoogleAuthConfig, val wsClient: WSClient, ctx: Deployments.Context)
  extends AuthActions
  with ApiKeyAuth
  with Controller {

  import DeploymentsController._

  val jestClient = ctx.jestClient

  val healthcheck = Action { Ok("OK") }

  val index = AuthAction { request =>
    implicit val user = request.user
    Ok(views.html.index())
  }

  def search(team: Option[String], service: Option[String], buildId: Option[String], result: Option[String], offset: Int) = AuthAction { request =>
    implicit val user = request.user
    val (teamQuery, serviceQuery, buildIdQuery, resultQuery) =
      (team.filter(_.nonEmpty), service.filter(_.nonEmpty), buildId.filter(_.nonEmpty), result.flatMap(DeploymentResult.fromLowerCaseString))
    val items = ES.Deployments.search(teamQuery, serviceQuery, buildIdQuery, resultQuery, offset).run(jestClient)
    Ok(views.html.deployments.search(items, teamQuery, serviceQuery, buildIdQuery, resultQuery))
  }

  def create = ApiKeyAuthAction { implicit request =>
    DeploymentForm.bindFromRequest.fold(_ => BadRequest, data => {
      Deployments.createDeployment(
        data.team,
        data.service,
        data.buildId,
        OffsetDateTime.now(),
        data.links.getOrElse(Nil),
        data.result.getOrElse(Succeeded)
      ).run(ctx)
      Ok("ok")
    })
  }

}

object DeploymentsController {

  case class DeploymentFormData(
    team: String,
    service: String,
    buildId: String,
    links: Option[List[Link]],
    result: Option[Product with Serializable with DeploymentResult]
  )

  private val deploymentResult = nonEmptyText
    .verifying(DeploymentResult.fromLowerCaseString(_).isDefined)
    .transform(DeploymentResult.fromLowerCaseString(_).get, DeploymentResult.toLowerCaseString)

  val DeploymentForm = Form(mapping(
    "team" -> nonEmptyText,
    "service" -> nonEmptyText,
    "buildId" -> nonEmptyText,
    "links" -> optional(list(mapping(
      "title" -> nonEmptyText,
      "url" -> nonEmptyText
    )(Link.apply)(Link.unapply))),
    "result" -> optional(deploymentResult)
  )(DeploymentFormData.apply)(DeploymentFormData.unapply))

}
