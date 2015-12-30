package io.vamp.persistence

import com.typesafe.config.ConfigFactory
import io.vamp.model.artifact._
import io.vamp.model.reader._
import io.vamp.model.serialization.CoreSerializationFormat
import io.vamp.pulse.ElasticsearchClient
import io.vamp.pulse.ElasticsearchClient.{ ElasticsearchSearchResponse, ElasticsearchGetResponse }
import org.json4s.DefaultFormats
import org.json4s.native.Serialization._

import scala.concurrent.Future

object ElasticsearchPersistenceActor {

  lazy val index = ConfigFactory.load().getString("vamp.persistence.elasticsearch.index")

  lazy val elasticsearchUrl: String = ConfigFactory.load().getString("vamp.persistence.elasticsearch.url")
}

case class ElasticsearchArtifact(name: String, artifact: String)

case class ElasticsearchPersistenceInfo(`type`: String, url: String, index: String, elasticsearch: Any)

class ElasticsearchPersistenceActor extends PersistenceActor with TypeOfArtifact with PaginationSupport {

  import ElasticsearchPersistenceActor._
  import YamlSourceReader._

  private val es = new ElasticsearchClient(elasticsearchUrl)

  protected def info(): Future[Any] = es.health.map {
    ElasticsearchPersistenceInfo("elasticsearch", elasticsearchUrl, index, _)
  }

  protected def all(`type`: Class[_ <: Artifact], page: Int, perPage: Int): Future[ArtifactResponseEnvelope] = {
    log.debug(s"${getClass.getSimpleName}: all [${type2string(`type`)}] of $page per $perPage")

    val from = (page - 1) * perPage
    es.search[ElasticsearchSearchResponse](index, `type`,
      s"""
         |{
         |  "query": {
         |    "filtered": {
         |      "query": {
         |        "match_all": {}
         |      }
         |    }
         |  },
         |  "from": $from,
         |  "size": $perPage
         |}
        """.stripMargin) map {
        case response ⇒
          val list = response.hits.hits.flatMap { hit ⇒
            readerOf(`type`).flatMap { reader ⇒ Option(reader.read(write(hit._source)(DefaultFormats))) }
          }
          ArtifactResponseEnvelope(list, response.hits.total, from, perPage)
      }
  }

  protected def get(name: String, `type`: Class[_ <: Artifact]): Future[Option[Artifact]] = {
    log.debug(s"${getClass.getSimpleName}: read [${type2string(`type`)}] - $name}")
    es.get[ElasticsearchGetResponse](index, `type`, name) map {
      case hit ⇒ if (hit.found) readerOf(`type`).flatMap { reader ⇒ Option(reader.read(write(hit._source)(DefaultFormats))) } else None
    }
  }

  protected def set(artifact: Artifact): Future[Artifact] = {
    val store = write(artifact)(CoreSerializationFormat.full)
    log.debug(s"${getClass.getSimpleName}: set [${artifact.getClass.getSimpleName}] - $store")
    es.index[Any](index, artifact.getClass, artifact.name, store).map { _ ⇒ artifact }
  }

  protected def delete(name: String, `type`: Class[_ <: Artifact]): Future[Option[Artifact]] = {
    log.debug(s"${getClass.getSimpleName}: delete [${`type`.getSimpleName}] - $name}")
    es.delete(index, `type`, name).map { _ ⇒ None }
  }

  private def readerOf(`type`: String): Option[YamlReader[_ <: Artifact]] = Map(
    "gateways" -> new AbstractGatewayReader[Gateway] {
      override protected def parse(implicit source: YamlSourceReader): Gateway = Gateway(name, port, sticky("sticky"), routes(splitPath = true), active)

      protected override def name(implicit source: YamlSourceReader): String = <<?[String]("name") match {
        case None       ⇒ AnonymousYamlReader.name
        case Some(name) ⇒ name
      }
    },
    "deployments" -> DeploymentReader,
    "breeds" -> BreedReader,
    "blueprints" -> BlueprintReader,
    "slas" -> SlaReader,
    "scales" -> ScaleReader,
    "escalations" -> EscalationReader,
    "routings" -> RouteReader,
    "filters" -> FilterReader,
    "workflows" -> WorkflowReader,
    "scheduled-workflows" -> ScheduledWorkflowReader
  ).get(`type`)
}
