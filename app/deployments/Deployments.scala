package deployments

import java.time.OffsetDateTime
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.tagless.FunctorK
import com.sksamuel.elastic4s.ElasticDsl.{createIndex, properties, _}
import com.sksamuel.elastic4s.cats.effect.instances._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.fields.{DateField, KeywordField, NestedField, TextField}
import com.sksamuel.elastic4s.requests.admin.UpdateIndexLevelSettingsRequest
import com.sksamuel.elastic4s.requests.indexes.CreateIndexRequest
import com.sksamuel.elastic4s.requests.searches.SearchRequest
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.{ElasticClient, Executor}
import deployments.Environment.{LoadTest, Nonprod, Prod}
import elasticsearch.Agg.{bucket, max}
import elasticsearch.CirceCodecs._
import elasticsearch.Instances._
import elasticsearch.Pagination._
import io.circe.Decoder.{decodeOption, decodeString}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import models.{Identified, Service}

trait Deployments[F[_]] {
  def create(deployment: Deployment): F[Identified[Deployment]]
  def search(terms: SearchTerms, page: Int): F[Page[Identified[Deployment]]]
  def recent(deployedInLastNDays: Int): F[Seq[Service]]
  def delete(id: String): F[Either[String, Unit]]
  def createIndex: F[Unit]
}

object Deployments {

  val indexName: String                  = "shipit_v3_deployments"
  implicit val fk: FunctorK[Deployments] = cats.tagless.Derive.functorK

  val maxResultWindow: UpdateIndexLevelSettingsRequest =
    updateIndexLevelSettings(List(indexName)).maxResultWindow(500000)

  /**
    * Old deployments don't have an environment set but are assumed to be prod
    * so if we search for prod we want anything that isn't explicitly nonprod
    */
  private def environmentFilter(environment: Option[Environment]): Option[Query] =
    environment match {
      case Some(LoadTest) => Some(matchQuery("environment", LoadTest.name))
      case Some(Nonprod)  => Some(matchQuery("environment", Nonprod.name))
      case Some(Prod)     => Some(not(matchQuery("environment", Nonprod.name)))
      case None           => None
    }

  private def searchFilters(terms: SearchTerms): Option[NonEmptyList[Query]] =
    NonEmptyList.fromList(
      terms.buildId.map(b => matchQuery("buildId", b)).toList ++
        terms.service.map(b => matchQuery("service", b)).toList ++
        terms.team.map(b => matchQuery("team", b)).toList ++
        environmentFilter(terms.environment)
    )

  private def searchQuery(terms: SearchTerms, page: Int): SearchRequest = {
    val query    = search(indexName).from(pageToOffset(page)).limit(PageSize).matchAllQuery()
    val filtered = searchFilters(terms).fold(query)(f => query.postFilter(must(f.head, f.tail: _*)))
    filtered.sortByFieldDesc("timestamp").trackTotalHits(true)
  }

  private def recentQuery(deployedInLastNDays: Int): SearchRequest =
    search(indexName)
      .query(rangeQuery("timestamp").gte(s"now-${deployedInLastNDays}d/d"))
      .size(0)
      .aggs(
        termsAgg("by_team", "team")
          .size(500)
          .addSubagg(
            termsAgg("by_service", "service")
              .size(500)
              .addSubagg(maxAgg("last_deployment", "timestamp"))
          )
      )

  private val createDeployments: CreateIndexRequest =
    createIndex(indexName)
      .mapping(
        properties(
          KeywordField("team"),
          KeywordField("service"),
          KeywordField("buildId"),
          KeywordField("environment"),
          DateField("timestamp"),
          TextField("note"),
          NestedField(
            name = "links",
            properties = List(
              KeywordField("title"),
              KeywordField("url")
            )
          )
        )
      )

  implicit val decodeServices: Decoder[List[Service]] =
    bucket("by_team")(bucket("by_service")(max[OffsetDateTime]("last_deployment"))).map { teams =>
      for {
        team    <- teams
        service <- team.subAgg
      } yield Service(team.key, service.key, service.subAgg.value)
    }

  def apply[F[_]: Executor](client: ElasticClient)(implicit F: Sync[F]): Deployments[F] =
    new Deployments[F] {

      implicit val envDec: Decoder[Environment] =
        decodeOption(decodeString.emap(Environment.fromString)).map(_.getOrElse(Prod))

      implicit val envEnc: Encoder[Environment] =
        Encoder.encodeString.contramap(_.name)

      implicit val linkEnc: Encoder[Link]      = deriveEncoder
      implicit val linkDec: Decoder[Link]      = deriveDecoder
      implicit val depEnc: Encoder[Deployment] = deriveEncoder[Deployment]
      implicit val depDec: Decoder[Deployment] = deriveDecoder[Deployment]

      def createIndex: F[Unit] =
        client.execute(createDeployments).void >>
          client.execute(maxResultWindow).void

      def create(dep: Deployment): F[Identified[Deployment]] =
        client.execute(indexInto(indexName).source(dep)).map(r => Identified(r.result.id, dep))

      def search(terms: SearchTerms, page: Int): F[Page[Identified[Deployment]]] =
        for {
          response <- client.execute(searchQuery(terms, page))
          data     <- response.result.safeTo[Identified[Deployment]].toList.traverse(F.fromTry)
        } yield Page(data, page, response.result.totalHits.toInt)

      def recent(deployedInLastNDays: Int): F[Seq[Service]] =
        for {
          response <- client.execute(recentQuery(deployedInLastNDays))
          aggs     <- F.fromTry(response.result.aggs.safeTo[List[Service]])
        } yield aggs

      def delete(id: String): F[Either[String, Unit]] =
        client.execute(deleteById(indexName, id)).map(_.toEither.bimap(_.reason, _ => ()))
    }
}
