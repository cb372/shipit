package datadog

import java.time.OffsetDateTime
import deployments.{Deployment, Environment, Link}
import io.circe.parser._
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json

class DatadogSpec extends AnyFlatSpec with Matchers with OptionValues {

  it should "build a payload for a Datadog event" in {
    val deployment = Deployment(
      team = "Team America",
      service = "my lovely service",
      environment = Environment.Prod,
      buildId = "123",
      timestamp = OffsetDateTime.now,
      links = List(
        Link("PR", "https://github.com/pr"),
        Link("CI", "https://circleci.com/build/123")
      ),
      note = Some("this build was awesome")
    )
    val payload = Datadog.buildPayload(deployment)
    val json    = parse(Json.stringify(payload)).toOption.get

    val expectedJson = parse(
      """
        |{
        | "title": "Deployment: Team America/my lovely service",
        | "text": "Service [Team America/my lovely service] was deployed successfully.",
        | "tags": [
        |   "shipit:deployment",
        |   "team:Team America",
        |   "service:my lovely service",
        |   "env:prod",
        |   "build_id:123"
        |  ]
        |}
      """.stripMargin
    ).toOption.get

    assert(json == expectedJson)
  }

}
