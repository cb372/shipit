package slack

import java.time.OffsetDateTime
import deployments.{Deployment, Environment, Link}
import io.circe.parser._
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json

class SlackSpec extends AnyFlatSpec with Matchers with OptionValues {

  it should "build a payload for a deployment with a note" in {
    val deployment = Deployment(
      team = "Team America",
      service = "my lovely service",
      buildId = "123",
      environment = Environment.Prod,
      timestamp = OffsetDateTime.now,
      links = List(
        Link("PR", "https://github.com/pr"),
        Link("CI", "https://circleci.com/build/123")
      ),
      note = Some("this build was awesome")
    )
    val payload = Slack.buildPayload(deployment, channel = None)
    val json    = parse(Json.stringify(payload)).toOption.get

    val expectedJson = parse(
      """
        |{
        | "attachments": [
        |   {
        |     "fallback": "Service [Team America/my lovely service] was deployed successfully. <https://shipit.ovotech.org.uk/deployments|See recent deployments>",
        |     "pretext": "Service [Team America/my lovely service] was deployed successfully. <https://shipit.ovotech.org.uk/deployments|See recent deployments>",
        |     "color": "#00D000",
        |     "fields": [
        |       {
        |         "title": "Build ID",
        |         "value": "123",
        |         "short": true
        |       },
        |       {
        |         "title": "Links",
        |         "value": "<https://github.com/pr|PR>, <https://circleci.com/build/123|CI>",
        |         "short": true
        |       },
        |       {
        |         "title": "Notes",
        |         "value": "this build was awesome",
        |         "short": false
        |       }
        |     ]
        |   }
        | ]
        |}
      """.stripMargin
    ).toOption.get

    assert(json == expectedJson)
  }

  it should "build a payload with a custom channel" in {
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
      note = None
    )
    val payload = Slack.buildPayload(deployment, channel = Some("my-channel"))
    val json    = parse(Json.stringify(payload)).toOption.get

    val expectedJson = parse(
      """
        |{
        | "channel": "my-channel",
        | "attachments": [
        |   {
        |     "fallback": "Service [Team America/my lovely service] was deployed successfully. <https://shipit.ovotech.org.uk/deployments|See recent deployments>",
        |     "pretext": "Service [Team America/my lovely service] was deployed successfully. <https://shipit.ovotech.org.uk/deployments|See recent deployments>",
        |     "color": "#00D000",
        |     "fields": [
        |       {
        |         "title": "Build ID",
        |         "value": "123",
        |         "short": true
        |       },
        |       {
        |         "title": "Links",
        |         "value": "<https://github.com/pr|PR>, <https://circleci.com/build/123|CI>",
        |         "short": true
        |       }
        |     ]
        |   }
        | ]
        |}
      """.stripMargin
    ).toOption.get

    assert(json == expectedJson)
  }

}
