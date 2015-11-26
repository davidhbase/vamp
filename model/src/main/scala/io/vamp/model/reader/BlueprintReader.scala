package io.vamp.model.reader

import io.vamp.model.artifact._
import io.vamp.model.notification._
import io.vamp.model.reader.YamlSourceReader._
import io.vamp.model.validator.BlueprintTraitValidator

import scala.language.postfixOps

trait AbstractBlueprintReader extends YamlReader[Blueprint] with ReferenceYamlReader[Blueprint] with TraitReader with DialectReader with BlueprintTraitValidator {

  override def readReference(any: Any): Blueprint = any match {
    case string: String ⇒ BlueprintReference(string)
    case yaml: YamlSourceReader ⇒
      implicit val source = yaml
      if (source.size > 1)
        read(source)
      else
        BlueprintReference(name)
    case _ ⇒ throwException(UnexpectedInnerElementError("/", classOf[YamlSourceReader]))
  }

  override protected def expand(implicit source: YamlSourceReader) = {
    <<?[YamlSourceReader]("clusters") match {
      case Some(yaml) ⇒ yaml.pull().map {
        case (name: String, breed: String) ⇒ >>("clusters" :: name :: "services", List(YamlSourceReader("breed" -> breed)))
        case (name: String, list: List[_]) ⇒ >>("clusters" :: name :: "services", list)
        case _                             ⇒
      }
      case _ ⇒
    }
    <<?[YamlSourceReader]("clusters") match {
      case Some(yaml) ⇒ yaml.pull().map {
        case (name: String, cluster: YamlSourceReader) ⇒
          implicit val source = cluster
          <<?[Any]("services") match {
            case None                ⇒ >>("services", List(source))
            case Some(list: List[_]) ⇒
            case Some(breed: String) ⇒ >>("services", List(YamlSourceReader("breed" -> breed)))
            case Some(m)             ⇒ >>("services", List(m))
          }
          >>("services", <<![List[_]]("services").map { element ⇒
            if (element.isInstanceOf[String]) {
              YamlSourceReader("breed" -> YamlSourceReader("reference" -> element))
            } else {
              implicit val source = element.asInstanceOf[YamlSourceReader]
              <<?[Any]("breed") match {
                case None ⇒
                  <<?[Any]("name") match {
                    case None ⇒ hasReference match {
                      case None      ⇒
                      case Some(ref) ⇒ >>("breed", ref)
                    }
                    case Some(_) ⇒ >>("breed", source)
                  }
                case _ ⇒
              }
              <<?[Any]("routing") match {
                case None            ⇒
                case Some(s: String) ⇒
                case Some(s)         ⇒ expandToList("routing" :: "filters")
              }
              expandDialect
              element
            }
          })
          expandDialect
        case _ ⇒
      }
      case _ ⇒
    }
    super.expand
  }

  private def expandDialect(implicit source: YamlSourceReader) = {
    <<?[Any]("dialects") match {
      case None ⇒ >>("dialects", YamlSourceReader(dialectValues.map { case (k, v) ⇒ k.toString.toLowerCase -> v }))
      case _    ⇒
    }
  }

  override def parse(implicit source: YamlSourceReader): Blueprint = {

    val clusters = <<?[YamlSourceReader]("clusters") match {
      case None ⇒ List[Cluster]()
      case Some(yaml) ⇒ yaml.pull().map {
        case (name: String, cluster: YamlSourceReader) ⇒
          implicit val source = cluster
          val sla = SlaReader.readOptionalReferenceOrAnonymous("sla")

          <<?[List[YamlSourceReader]]("services") match {
            case None       ⇒ Cluster(name, List(), sla, dialects)
            case Some(list) ⇒ Cluster(name, list.map(parseService(_)), sla, dialects)
          }
      } toList
    }

    val endpoints = ports("endpoints", addGroup = true)
    val evs = environmentVariables(alias = false, addGroup = true)

    DefaultBlueprint(name, clusters, endpoints, evs)
  }

  override protected def validate(bp: Blueprint): Blueprint = bp match {
    case blueprint: BlueprintReference ⇒ blueprint
    case blueprint: DefaultBlueprint ⇒

      validateBlueprintTraitValues(blueprint)
      validateRoutingWeights(blueprint)

      if (blueprint.clusters.flatMap(_.services).count(_ ⇒ true) == 0) throwException(NoServiceError)

      val breeds = blueprint.clusters.flatMap(_.services.map(_.breed))
      validateBreeds(breeds)
      validateServiceEnvironmentVariables(blueprint.clusters.flatMap(_.services))
      validateDependencies(breeds)
      breeds.foreach(BreedReader.validateNonRecursiveDependencies)

      blueprint
  }

  protected def validateRoutingWeights(blueprint: DefaultBlueprint): Unit = {
    blueprint.clusters.find({ cluster ⇒
      val weights = cluster.services.flatMap(_.routing).filter(_.isInstanceOf[DefaultRouting]).map(_.asInstanceOf[DefaultRouting]).flatMap(_.weight)
      weights.exists(_ < 0) || weights.sum > 100
    }).flatMap {
      case cluster ⇒ throwException(RoutingWeightError(cluster))
    }
  }

  protected def validateBreeds(breeds: List[Breed]): Unit = {
    breeds.groupBy(_.name.toString).collect {
      case (name, list) if list.size > 1 ⇒ throwException(NonUniqueBlueprintBreedReferenceError(name))
    }
  }

  protected def validateServiceEnvironmentVariables(services: List[Service]) = services.foreach { service ⇒
    service.breed match {
      case breed: DefaultBreed ⇒ service.environmentVariables.foreach { environmentVariable ⇒
        if (environmentVariable.value.isEmpty) throwException(MissingEnvironmentVariableError(breed, environmentVariable.name))
        if (!breed.environmentVariables.exists(_.name == environmentVariable.name)) throwException(UnresolvedDependencyInTraitValueError(breed, environmentVariable.name))
      }
      case _ ⇒
    }
  }

  protected def validateDependencies(breeds: List[Breed]): Unit = {
    breeds.flatMap({
      case breed: DefaultBreed ⇒ breed.dependencies.map((breed, _))
      case _                   ⇒ List()
    }).find({
      case (breed, dependency) ⇒ !breeds.exists(_.name == dependency._2.name)
    }).flatMap {
      case (breed, dependency) ⇒ throwException(UnresolvedBreedDependencyError(breed, dependency))
    }
  }

  private def parseService(implicit source: YamlSourceReader): Service =
    Service(BreedReader.readReference(<<![Any]("breed")), environmentVariables(alias = false), ScaleReader.readOptionalReferenceOrAnonymous("scale"), RoutingReader.readOptionalReferenceOrAnonymous("routing"), dialects)
}

object BlueprintReader extends AbstractBlueprintReader {

  override protected def validate(blueprint: Blueprint) = {
    super.validate(blueprint)
    blueprint match {
      case bp: AbstractBlueprint ⇒ validateScaleEscalations(bp)
      case _                     ⇒
    }
    blueprint
  }

  def validateScaleEscalations(blueprint: AbstractBlueprint): Unit = {
    blueprint.clusters.foreach { cluster ⇒
      cluster.sla match {
        case None ⇒
        case Some(s) ⇒ s.escalations.foreach {
          case escalation: ScaleEscalation[_] ⇒ escalation.targetCluster match {
            case None ⇒
            case Some(clusterName) ⇒ blueprint.clusters.find(_.name == clusterName) match {
              case None    ⇒ throwException(UnresolvedScaleEscalationTargetCluster(cluster, clusterName))
              case Some(_) ⇒
            }
          }
          case _ ⇒
        }
      }
    }
  }
}

object DeploymentBlueprintReader extends AbstractBlueprintReader {
  override protected def validateDependencies(breeds: List[Breed]): Unit = {}
}

object ScaleReader extends YamlReader[Scale] with WeakReferenceYamlReader[Scale] {

  override protected def createReference(implicit source: YamlSourceReader): Scale = ScaleReference(reference)

  override protected def createDefault(implicit source: YamlSourceReader): Scale = DefaultScale(name, <<![Double]("cpu"), <<![Double]("memory"), <<![Int]("instances"))
}

object RoutingReader extends YamlReader[Routing] with WeakReferenceYamlReader[Routing] {

  import YamlSourceReader._

  override protected def createReference(implicit source: YamlSourceReader): Routing = RoutingReference(reference)

  override protected def createDefault(implicit source: YamlSourceReader): Routing = DefaultRouting(name, <<?[Int]("weight"), filters)

  protected def filters(implicit source: YamlSourceReader): List[Filter] = <<?[YamlList]("filters") match {
    case None ⇒ List[Filter]()
    case Some(list: YamlList) ⇒ list.map {
      FilterReader.readReferenceOrAnonymous
    }
  }
}

object FilterReader extends YamlReader[Filter] with WeakReferenceYamlReader[Filter] {

  override protected def createReference(implicit source: YamlSourceReader): Filter = FilterReference(reference)

  override protected def createDefault(implicit source: YamlSourceReader): Filter = DefaultFilter(name, <<![String]("condition"))
}
