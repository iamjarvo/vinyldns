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

package vinyldns.api.engine.sqs

import vinyldns.api.Interfaces._
import vinyldns.api.domain.engine.EngineCommandBus
import vinyldns.api.domain.record.RecordSetChange
import vinyldns.api.domain.zone.{ZoneCommand, ZoneCommandResult}

import scala.concurrent.{ExecutionContext, Future}

class SqsCommandBus(sqsConnection: SqsConnection)(implicit ec: ExecutionContext)
    extends EngineCommandBus {
  import SqsConverters._

  def sendZoneCommand(cmd: ZoneCommand): Result[ZoneCommandResult] =
    sendCommand(cmd, sqsConnection)
      .unsafeToFuture()
      .map(c => c.asInstanceOf[ZoneCommandResult])
      .toResult

  def sendRecordSetChanges(cmds: List[RecordSetChange]): List[Future[RecordSetChange]] =
    // TODO - do this smarter, shouldnt put on one by one
    cmds.map { cmd =>
      sendCommand(cmd, sqsConnection)
        .unsafeToFuture()
        .map(c => c.asInstanceOf[RecordSetChange])
    }
}
