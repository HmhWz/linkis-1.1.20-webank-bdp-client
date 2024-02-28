/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.linkis.manager.rm.service.impl

import org.apache.linkis.manager.am.conf.AMConfiguration
import org.apache.linkis.manager.am.vo.CanCreateECRes
import org.apache.linkis.manager.common.constant.RMConstant
import org.apache.linkis.manager.common.entity.resource._
import org.apache.linkis.manager.common.entity.resource.ResourceType.DriverAndYarn
import org.apache.linkis.manager.common.exception.RMWarnException
import org.apache.linkis.manager.common.protocol.engine.EngineCreateRequest
import org.apache.linkis.manager.rm.domain.RMLabelContainer
import org.apache.linkis.manager.rm.exception.RMErrorCode
import org.apache.linkis.manager.rm.external.service.ExternalResourceService
import org.apache.linkis.manager.rm.external.yarn.YarnResourceIdentifier
import org.apache.linkis.manager.rm.service.{LabelResourceService, RequestResourceService}
import org.apache.linkis.manager.rm.utils.{AcrossClusterRulesJudgeUtils, RMUtils}

import org.apache.commons.lang3.StringUtils

import org.json4s.DefaultFormats

class DriverAndYarnReqResourceService(
    labelResourceService: LabelResourceService,
    externalResourceService: ExternalResourceService
) extends RequestResourceService(labelResourceService) {

  implicit val formats = DefaultFormats + ResourceSerializer

  override val resourceType: ResourceType = DriverAndYarn

  override def canRequestResource(
      labelContainer: RMLabelContainer,
      resource: NodeResource,
      engineCreateRequest: EngineCreateRequest
  ): CanCreateECRes = {
    val canCreateECRes = super.canRequestResource(labelContainer, resource, engineCreateRequest)
    if (!canCreateECRes.isCanCreateEC) {
      return canCreateECRes
    }
    val requestedDriverAndYarnResource =
      resource.getMaxResource.asInstanceOf[DriverAndYarnResource]
    val requestedYarnResource = requestedDriverAndYarnResource.yarnResource
    val yarnIdentifier = new YarnResourceIdentifier(requestedYarnResource.queueName)
    val providedYarnResource =
      externalResourceService.getResource(ResourceType.Yarn, labelContainer, yarnIdentifier)
    val (maxCapacity, usedCapacity) =
      (providedYarnResource.getMaxResource, providedYarnResource.getUsedResource)
    logger.debug(
      s"This queue: ${requestedYarnResource.queueName} used resource:$usedCapacity and max resource: $maxCapacity"
    )
    val queueLeftResource = maxCapacity - usedCapacity
    logger.info(
      s"queue: ${requestedYarnResource.queueName} left $queueLeftResource, this request requires: $requestedYarnResource"
    )
    if (queueLeftResource < requestedYarnResource) {
      logger.info(
        s"user: ${labelContainer.getUserCreatorLabel.getUser} request queue resource $requestedYarnResource > left resource $queueLeftResource"
      )

      val notEnoughMessage =
        generateQueueNotEnoughMessage(requestedYarnResource, queueLeftResource, maxCapacity)
      canCreateECRes.setCanCreateEC(false);
      canCreateECRes.setReason(notEnoughMessage._2)
    }
    canCreateECRes.setYarnResource(RMUtils.serializeResource(queueLeftResource))
    canCreateECRes
  }

  override def canRequest(
      labelContainer: RMLabelContainer,
      resource: NodeResource,
      engineCreateRequest: EngineCreateRequest
  ): Boolean = {
    if (!super.canRequest(labelContainer, resource, engineCreateRequest)) {
      return false
    }

    val requestedDriverAndYarnResource =
      resource.getMaxResource.asInstanceOf[DriverAndYarnResource]
    val requestedYarnResource = requestedDriverAndYarnResource.yarnResource
    val yarnIdentifier = new YarnResourceIdentifier(requestedYarnResource.queueName)
    val providedYarnResource =
      externalResourceService.getResource(ResourceType.Yarn, labelContainer, yarnIdentifier)
    val (maxCapacity, usedCapacity) =
      (providedYarnResource.getMaxResource, providedYarnResource.getUsedResource)
    logger.debug(
      s"This queue: ${requestedYarnResource.queueName} used resource:$usedCapacity and max resource: $maxCapacity"
    )
    val queueLeftResource = maxCapacity - usedCapacity
    logger.info(
      s"queue: ${requestedYarnResource.queueName} left $queueLeftResource, this request requires: $requestedYarnResource"
    )
    if (queueLeftResource < requestedYarnResource) {
      logger.info(
        s"user: ${labelContainer.getUserCreatorLabel.getUser} request queue resource $requestedYarnResource > left resource $queueLeftResource"
      )

      // bdap resource not enough, judge bdap queue resource threshold
      val acrossClusterTask =
        engineCreateRequest.getProperties.getOrDefault(AMConfiguration.ACROSS_CLUSTER_TASK, "false")
      val priorityCluster = engineCreateRequest.getProperties.get(AMConfiguration.PRIORITY_CLUSTER)
      // bdp resource no need enter

      logger.info(s"acrossClusterTask: $acrossClusterTask and priorityCluster: $priorityCluster")

      if (
          StringUtils.isNotBlank(acrossClusterTask) && acrossClusterTask.toBoolean && StringUtils
            .isNotBlank(priorityCluster) && priorityCluster.equals(
            AMConfiguration.PRIORITY_CLUSTER_ORIGIN
          )
      ) {

        logger.info("queue real resource not enough, and judge origin queue threshold")

        val originCPUPercentageThreshold =
          engineCreateRequest.getProperties.get(AMConfiguration.ORIGIN_CPU_PERCENTAGE_THRESHOLD)
        val originMemoryPercentageThreshold =
          engineCreateRequest.getProperties.get(AMConfiguration.ORIGIN_MEMORY_PERCENTAGE_THRESHOLD)

        if (
            StringUtils.isNotBlank(originCPUPercentageThreshold) && StringUtils.isNotBlank(
              originMemoryPercentageThreshold
            )
        ) {
          try {
            AcrossClusterRulesJudgeUtils.originClusterRuleCheck(
              usedCapacity.asInstanceOf[YarnResource],
              maxCapacity.asInstanceOf[YarnResource],
              originCPUPercentageThreshold.toDouble,
              originMemoryPercentageThreshold.toDouble
            )
          } catch {
            case ex: Exception =>
              throw new RMWarnException(
                RMErrorCode.ACROSS_CLUSTER_RULE_FAILED.getErrorCode,
                ex.getMessage
              )
          }
        }
      }

      val notEnoughMessage =
        generateQueueNotEnoughMessage(requestedYarnResource, queueLeftResource, maxCapacity)
      throw new RMWarnException(notEnoughMessage._1, notEnoughMessage._2)

    }

    if (engineCreateRequest.getProperties != null) {
      val user = labelContainer.getUserCreatorLabel.getUser
      val creator = labelContainer.getUserCreatorLabel.getCreator
      val properties = engineCreateRequest.getProperties
      val acrossClusterTask = properties.getOrDefault(AMConfiguration.ACROSS_CLUSTER_TASK, "false")
      val priorityCluster = properties.get(AMConfiguration.PRIORITY_CLUSTER)

      if (
          StringUtils.isNotBlank(acrossClusterTask) && acrossClusterTask.toBoolean && StringUtils
            .isNotBlank(priorityCluster) && priorityCluster.equals(
            AMConfiguration.PRIORITY_CLUSTER_TARGET
          )
      ) {

        // cross cluster task and bdp priority
        val targetCPUThreshold = properties.get(AMConfiguration.TARGET_CPU_THRESHOLD)
        val targetMemoryThreshold = properties.get(AMConfiguration.TARGET_MEMORY_THRESHOLD)
        val targetCPUPercentageThreshold =
          properties.get(AMConfiguration.TARGET_CPU_PERCENTAGE_THRESHOLD)
        val targetMemoryPercentageThreshold =
          properties.get(AMConfiguration.TARGET_MEMORY_PERCENTAGE_THRESHOLD)

        if (
            StringUtils
              .isNotBlank(targetCPUThreshold) && StringUtils.isNotBlank(targetMemoryThreshold)
            && StringUtils.isNotBlank(targetCPUPercentageThreshold) && StringUtils.isNotBlank(
              targetMemoryPercentageThreshold
            )
        ) {

          // judge total cluster resources
          val clusterYarnResource =
            externalResourceService.getResource(
              ResourceType.Yarn,
              labelContainer,
              new YarnResourceIdentifier("root")
            )
          val (clusterMaxCapacity, clusterUsedCapacity) =
            (clusterYarnResource.getMaxResource, clusterYarnResource.getUsedResource)

          val clusterCPUPercentageThreshold =
            AMConfiguration.ACROSS_CLUSTER_TOTAL_CPU_PERCENTAGE_THRESHOLD
          val clusterMemoryPercentageThreshold =
            AMConfiguration.ACROSS_CLUSTER_TOTAL_MEMORY_PERCENTAGE_THRESHOLD

          logger.info(
            s"user: $user, creator: $creator, priorityCluster: $priorityCluster, " +
              s"targetCPUThreshold: $targetCPUThreshold, targetMemoryThreshold: $targetMemoryThreshold," +
              s"targetCPUPercentageThreshold: $targetCPUPercentageThreshold, targetMemoryPercentageThreshold: $targetMemoryPercentageThreshold" +
              s"clusterCPUPercentageThreshold: $clusterCPUPercentageThreshold, clusterMemoryPercentageThreshold: $clusterMemoryPercentageThreshold"
          )

          // judge bdp cluster queue resources
          try {
            AcrossClusterRulesJudgeUtils.targetClusterRuleCheck(
              queueLeftResource.asInstanceOf[YarnResource],
              usedCapacity.asInstanceOf[YarnResource],
              maxCapacity.asInstanceOf[YarnResource],
              clusterMaxCapacity.asInstanceOf[YarnResource],
              clusterUsedCapacity.asInstanceOf[YarnResource],
              targetCPUThreshold.toInt,
              targetMemoryThreshold.toInt,
              targetCPUPercentageThreshold.toDouble,
              targetMemoryPercentageThreshold.toDouble,
              clusterCPUPercentageThreshold,
              clusterMemoryPercentageThreshold
            )
          } catch {
            case ex: Exception =>
              throw new RMWarnException(
                RMErrorCode.ACROSS_CLUSTER_RULE_FAILED.getErrorCode,
                ex.getMessage
              )
          }
          logger.info(s"user: $user, creator: $creator task meet the threshold rule")
        } else {
          logger.info(s"user: $user, creator: $creator task skip cross cluster resource judgment")
        }
      } else if (
          StringUtils.isNotBlank(acrossClusterTask) && acrossClusterTask.toBoolean && StringUtils
            .isNotBlank(priorityCluster) && priorityCluster.equals(
            AMConfiguration.PRIORITY_CLUSTER_ORIGIN
          )
      ) {

        // cross cluster task and bdap priority
        val originCPUPercentageThreshold =
          properties.get(AMConfiguration.ORIGIN_CPU_PERCENTAGE_THRESHOLD)
        val originMemoryPercentageThreshold =
          properties.get(AMConfiguration.ORIGIN_MEMORY_PERCENTAGE_THRESHOLD)

        if (
            StringUtils.isNotBlank(originCPUPercentageThreshold) && StringUtils.isNotBlank(
              originMemoryPercentageThreshold
            )
        ) {

          logger.info(
            s"user: $user, creator: $creator, priorityCluster: $priorityCluster, " +
              s"originCPUPercentageThreshold: $originCPUPercentageThreshold, originMemoryPercentageThreshold: $originMemoryPercentageThreshold"
          )

          // judge bdap cluster queue resources
          try {
            AcrossClusterRulesJudgeUtils.originClusterRuleCheck(
              usedCapacity.asInstanceOf[YarnResource],
              maxCapacity.asInstanceOf[YarnResource],
              originCPUPercentageThreshold.toDouble,
              originMemoryPercentageThreshold.toDouble
            )
          } catch {
            case ex: Exception =>
              throw new RMWarnException(
                RMErrorCode.ACROSS_CLUSTER_RULE_FAILED.getErrorCode,
                ex.getMessage
              )
          }
          logger.info(s"user: $user, creator: $creator task meet the threshold rule")
        } else {
          logger.info(s"user: $user, creator: $creator task skip cross cluster resource judgment")
        }
      } else {
        logger.info(s"user: $user, creator: $creator task skip cross cluster resource judgment")
      }
    }

    true
  }

  def generateQueueNotEnoughMessage(
      requestResource: Resource,
      availableResource: Resource,
      maxResource: Resource
  ): (Int, String) = {
    requestResource match {
      case yarn: YarnResource =>
        val yarnAvailable = availableResource.asInstanceOf[YarnResource]
        val maxYarn = maxResource.asInstanceOf[YarnResource]
        if (yarn.queueCores > yarnAvailable.queueCores) {
          (
            RMErrorCode.CLUSTER_QUEUE_CPU_INSUFFICIENT.getErrorCode,
            RMErrorCode.CLUSTER_QUEUE_CPU_INSUFFICIENT.getErrorDesc +
              RMUtils.getResourceInfoMsg(
                RMConstant.CPU,
                RMConstant.CPU_UNIT,
                yarn.queueCores,
                yarnAvailable.queueCores,
                maxYarn.queueCores,
                yarn.queueName
              )
          )
        } else if (yarn.queueMemory > yarnAvailable.queueMemory) {
          (
            RMErrorCode.CLUSTER_QUEUE_MEMORY_INSUFFICIENT.getErrorCode,
            RMErrorCode.CLUSTER_QUEUE_MEMORY_INSUFFICIENT.getErrorDesc +
              RMUtils.getResourceInfoMsg(
                RMConstant.MEMORY,
                RMConstant.MEMORY_UNIT_BYTE,
                yarn.queueMemory,
                yarnAvailable.queueMemory,
                maxYarn.queueMemory,
                yarn.queueName
              )
          )
        } else {
          (
            RMErrorCode.CLUSTER_QUEUE_INSTANCES_INSUFFICIENT.getErrorCode,
            RMErrorCode.CLUSTER_QUEUE_INSTANCES_INSUFFICIENT.getErrorDesc +
              RMUtils.getResourceInfoMsg(
                RMConstant.APP_INSTANCE,
                RMConstant.INSTANCE_UNIT,
                yarn.queueInstances,
                yarnAvailable.queueInstances,
                maxYarn.queueInstances,
                yarn.queueName
              )
          )
        }
      case _ =>
        (
          RMErrorCode.CLUSTER_QUEUE_MEMORY_INSUFFICIENT.getErrorCode,
          RMErrorCode.CLUSTER_QUEUE_MEMORY_INSUFFICIENT.getErrorDesc + " Unusual insufficient queue memory."
        )
    }
  }

}
