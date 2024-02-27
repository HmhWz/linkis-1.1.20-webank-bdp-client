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

package org.apache.linkis.manager.rm.utils

import org.apache.linkis.common.utils.Logging
import org.apache.linkis.manager.common.constant.AMConstant
import org.apache.linkis.manager.common.entity.resource.YarnResource
import org.apache.linkis.manager.common.exception.RMWarnException
import org.apache.linkis.manager.rm.exception.RMErrorCode

object AcrossClusterRulesJudgeUtils extends Logging {

  def targetClusterRuleCheck(
      leftResource: YarnResource,
      usedResource: YarnResource,
      maxResource: YarnResource,
      clusterMaxCapacity: YarnResource,
      clusterUsedCapacity: YarnResource,
      CPUThreshold: Int,
      MemoryThreshold: Int,
      CPUPercentageThreshold: Double,
      MemoryPercentageThreshold: Double,
      clusterCPUPercentageThreshold: Double,
      clusterMemoryPercentageThreshold: Double
  ): Unit = {
    if (
        leftResource != null && usedResource != null && maxResource != null && clusterMaxCapacity != null && clusterUsedCapacity != null
    ) {

      val clusterUsedCPUPercentage = clusterUsedCapacity.queueCores
        .asInstanceOf[Double] / clusterMaxCapacity.queueCores.asInstanceOf[Double]
      val clusterUsedMemoryPercentage = clusterUsedCapacity.queueMemory
        .asInstanceOf[Double] / clusterMaxCapacity.queueMemory.asInstanceOf[Double]

      if (
          clusterUsedCPUPercentage >= clusterCPUPercentageThreshold || clusterUsedMemoryPercentage >= clusterMemoryPercentageThreshold
      ) {
        throw new RMWarnException(
          RMErrorCode.ACROSS_CLUSTER_RULE_FAILED.getErrorCode,
          s"clusterUsedCPUPercentage: $clusterUsedCPUPercentage, CPUPercentageThreshold: $clusterCPUPercentageThreshold" +
            s"clusterUsedMemoryPercentage: $clusterUsedMemoryPercentage, MemoryPercentageThreshold: $clusterMemoryPercentageThreshold"
        )
      }

      val leftQueueMemory = leftResource.queueMemory / Math.pow(1024, 3).toLong
      if (leftResource.queueCores > CPUThreshold && leftQueueMemory > MemoryThreshold) {
        val usedCPUPercentage =
          usedResource.queueCores.asInstanceOf[Double] / maxResource.queueCores
            .asInstanceOf[Double]
        val usedMemoryPercentage = usedResource.queueMemory
          .asInstanceOf[Double] / maxResource.queueMemory.asInstanceOf[Double]

        logger.info(
          "cross cluster test in target rule check" + s"usedCPUPercentage: $usedCPUPercentage, CPUPercentageThreshold: $CPUPercentageThreshold" +
            s"usedMemoryPercentage: $usedMemoryPercentage, MemoryPercentageThreshold: $MemoryPercentageThreshold"
        )

        if (
            usedCPUPercentage >= CPUPercentageThreshold || usedMemoryPercentage >= MemoryPercentageThreshold
        ) {
          throw new RMWarnException(
            RMErrorCode.ACROSS_CLUSTER_RULE_FAILED.getErrorCode,
            s"usedCPUPercentage: $usedCPUPercentage, CPUPercentageThreshold: $CPUPercentageThreshold" +
              s"usedMemoryPercentage: $usedMemoryPercentage, MemoryPercentageThreshold: $MemoryPercentageThreshold"
          )
        }
      } else {
        throw new RMWarnException(
          RMErrorCode.ACROSS_CLUSTER_RULE_FAILED.getErrorCode,
          s"leftResource.queueCores: ${leftResource.queueCores}, CPUThreshold: $CPUThreshold," +
            s"leftQueueMemory: $leftQueueMemory, MemoryThreshold: $MemoryThreshold"
        )
      }
    }
  }

  def originClusterRuleCheck(
      usedResource: YarnResource,
      maxResource: YarnResource,
      CPUPercentageThreshold: Double,
      MemoryPercentageThreshold: Double
  ): Unit = {
    if (usedResource != null && maxResource != null) {

      val usedCPUPercentage =
        usedResource.queueCores.asInstanceOf[Double] / maxResource.queueCores
          .asInstanceOf[Double]
      val usedMemoryPercentage = usedResource.queueMemory
        .asInstanceOf[Double] / maxResource.queueMemory.asInstanceOf[Double]

      logger.info(
        "cross cluster test in origin rule check" + s"usedCPUPercentage: $usedCPUPercentage, CPUPercentageThreshold: $CPUPercentageThreshold" +
          s"usedMemoryPercentage: $usedMemoryPercentage, MemoryPercentageThreshold: $MemoryPercentageThreshold"
      )

      if (
          usedCPUPercentage >= CPUPercentageThreshold || usedMemoryPercentage >= MemoryPercentageThreshold
      ) {
        throw new RMWarnException(
          RMErrorCode.ACROSS_CLUSTER_RULE_FAILED.getErrorCode,
          AMConstant.ORIGIN_CLUSTER_RETRY_DES
        )
      }
    }
  }

}
