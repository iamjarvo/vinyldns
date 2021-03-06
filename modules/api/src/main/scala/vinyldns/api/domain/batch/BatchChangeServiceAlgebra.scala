/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.domain.batch

import vinyldns.api.domain.auth.AuthPrincipal
import vinyldns.api.domain.batch.BatchChangeInterfaces.BatchResult

// $COVERAGE-OFF$
trait BatchChangeServiceAlgebra {
  def applyBatchChange(
      batchChangeInput: BatchChangeInput,
      auth: AuthPrincipal): BatchResult[BatchChange]

  def getBatchChange(id: String, auth: AuthPrincipal): BatchResult[BatchChange]

  def listBatchChangeSummaries(
      auth: AuthPrincipal,
      startFrom: Option[Int],
      maxItems: Int): BatchResult[BatchChangeSummaryList]
}
// $COVERAGE-ON$
