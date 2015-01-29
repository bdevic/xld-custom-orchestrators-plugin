/**
 * THIS CODE AND INFORMATION ARE PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS
 * FOR A PARTICULAR PURPOSE. THIS CODE AND INFORMATION ARE NOT SUPPORTED BY XEBIALABS.
 */
package com.xebialabs.community.xldeploy.orchestrators.throttling

import com.xebialabs.community.xldeploy.orchestrators.Descriptions._
import com.xebialabs.deployit.engine.spi.orchestration.Orchestrations._
import com.xebialabs.deployit.engine.spi.orchestration.{InterleavedOrchestration, Orchestration, Orchestrator}
import com.xebialabs.deployit.plugin.api.deployment.specification.{Delta, DeltaSpecification, Operation}
import com.xebialabs.deployit.plugin.api.udm.{Container}

import scala.collection.convert.wrapAll._

@Orchestrator.Metadata(name = "parallel-by-container-throttled", description = "The throttled parallel by container orchestrator")
class ThrottlingParallelContainerOrchestrator extends Orchestrator {

  import com.xebialabs.community.xldeploy.orchestrators.RichDelta._
  type DeltasForContainer = (Container, List[Delta])

  def orchestrate(spec: DeltaSpecification): Orchestration = getOrchestrations(spec)

  def getOrchestrations(spec: DeltaSpecification): Orchestration = {

    def toInterleaved(list: List[DeltasForContainer]): List[InterleavedOrchestration] = {
      list.map { case (c, ds) => interleaved(getDescriptionForContainer(spec.getOperation, c), ds)}
    }

    def stringOrderForOperation = if (spec.getOperation == Operation.DESTROY) Ordering.String.reverse else Ordering.String

    val desc: String = getDescriptionForSpec(spec)
    val deltasByContainer: Map[Container, List[Delta]] = spec.getDeltas.toList.groupBy(_.container)
    val sorted: List[DeltasForContainer] = deltasByContainer.toList.sortBy(_._1.getName)(stringOrderForOperation)

    val throttleProperty = Option(spec.getDeployedApplication.getType.getDescriptor.getPropertyDescriptor("maxContainersInParallel"))
    throttleProperty.map(_.get(spec.getDeployedApplication).asInstanceOf[Int]) match {
      case Some(mcip) if mcip >= 1 && sorted.size > mcip =>
        val chunked: Iterator[List[(Container, List[Delta])]] = sorted.grouped(mcip)
        val pars = chunked.map({ l => parallel(getDescriptionForContainers(spec.getOperation, l.map(_._1)), toInterleaved(l))}).toList
        serial(desc, pars)
      case _ =>
        parallel(desc, toInterleaved(sorted))
    }
  }

}