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

package vinyldns.api.repository.dynamodb

import java.util
import java.util.HashMap

import com.amazonaws.services.dynamodbv2.model.{CreateTableRequest, Projection, _}
import com.typesafe.config.Config
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import vinyldns.api.VinylDNSConfig
import vinyldns.api.domain.membership.GroupStatus.GroupStatus
import vinyldns.api.domain.membership.{Group, GroupRepository, GroupStatus}
import vinyldns.api.route.Monitored

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object DynamoDBGroupRepository {

  def apply(
      config: Config = VinylDNSConfig.groupsStoreConfig,
      dynamoConfig: Config = VinylDNSConfig.dynamoConfig): DynamoDBGroupRepository =
    new DynamoDBGroupRepository(
      config,
      new DynamoDBHelper(
        DynamoDBClient(dynamoConfig),
        LoggerFactory.getLogger(classOf[DynamoDBGroupRepository])))
}

class DynamoDBGroupRepository(
    config: Config = VinylDNSConfig.groupsStoreConfig,
    dynamoDBHelper: DynamoDBHelper)
    extends GroupRepository
    with Monitored {

  val log: Logger = LoggerFactory.getLogger(classOf[DynamoDBGroupRepository])

  private[repository] val GROUP_ID = "group_id"
  private val NAME = "name"
  private val EMAIL = "email"
  private val DESCRIPTION = "desc"
  private val CREATED = "created"
  private val STATUS = "status"
  private val MEMBER_IDS = "member_ids"
  private val ADMIN_IDS = "admin_ids"
  private val GROUP_NAME_INDEX = "group_name_index"

  private val dynamoReads = config.getLong("dynamo.provisionedReads")
  private val dynamoWrites = config.getLong("dynamo.provisionedWrites")
  private[repository] val GROUP_TABLE = config.getString("dynamo.tableName")

  private[repository] val tableAttributes = Seq(
    new AttributeDefinition(GROUP_ID, "S"),
    new AttributeDefinition(NAME, "S")
  )

  private[repository] val secondaryIndexes = Seq(
    new GlobalSecondaryIndex()
      .withIndexName(GROUP_NAME_INDEX)
      .withProvisionedThroughput(new ProvisionedThroughput(dynamoReads, dynamoWrites))
      .withKeySchema(new KeySchemaElement(NAME, KeyType.HASH))
      .withProjection(new Projection().withProjectionType("ALL"))
  )

  dynamoDBHelper.setupTable(
    new CreateTableRequest()
      .withTableName(GROUP_TABLE)
      .withAttributeDefinitions(tableAttributes: _*)
      .withKeySchema(new KeySchemaElement(GROUP_ID, KeyType.HASH))
      .withGlobalSecondaryIndexes(secondaryIndexes: _*)
      .withProvisionedThroughput(new ProvisionedThroughput(dynamoReads, dynamoWrites))
  )

  def loadData: Future[List[Group]] = Await.ready(GroupRepository.loadTestData(this), 100.seconds)
  loadData

  def save(group: Group): Future[Group] =
    monitor("repo.Group.save") {
      log.info(s"Saving group ${group.id} ${group.name}.")
      val item = toItem(group)
      val request = new PutItemRequest().withTableName(GROUP_TABLE).withItem(item)
      dynamoDBHelper.putItem(request).map(_ => group)
    }

  /*Looks up a group.  If the group is not found, or if the group's status is Deleted, will return None */
  def getGroup(groupId: String): Future[Option[Group]] =
    monitor("repo.Group.getGroup") {
      log.info(s"Getting group $groupId.")
      val key = new HashMap[String, AttributeValue]()
      key.put(GROUP_ID, new AttributeValue(groupId))
      val request = new GetItemRequest().withTableName(GROUP_TABLE).withKey(key)

      dynamoDBHelper
        .getItem(request)
        .map { result =>
          Option(result.getItem)
            .map(fromItem)
            .filter(_.status != GroupStatus.Deleted)
        }
    }

  def getGroups(groupIds: Set[String]): Future[Set[Group]] = {

    def toBatchGetItemRequest(groupIds: Set[String]): BatchGetItemRequest = {
      val allKeys = new util.ArrayList[util.Map[String, AttributeValue]]()

      for {
        groupId <- groupIds
      } {
        val key = new util.HashMap[String, AttributeValue]()
        key.put(GROUP_ID, new AttributeValue(groupId))
        allKeys.add(key)
      }

      val keysAndAttributes = new KeysAndAttributes().withKeys(allKeys)

      val request = new util.HashMap[String, KeysAndAttributes]()
      request.put(GROUP_TABLE, keysAndAttributes)

      new BatchGetItemRequest().withRequestItems(request)
    }

    def parseGroups(result: BatchGetItemResult): Set[Group] = {
      val groupAttributes = result.getResponses.asScala.get(GROUP_TABLE)
      groupAttributes match {
        case None =>
          Set()
        case Some(items) =>
          items.asScala.toSet.map(fromItem).filter(_.status != GroupStatus.Deleted)
      }
    }

    monitor("repo.Group.getGroups") {
      log.info(s"Getting groups by id $groupIds")

      // Group the group ids into batches of 100, that is the max size of the BatchGetItemRequest
      val batches = groupIds.grouped(100).toSet

      val batchGets = batches.map(toBatchGetItemRequest)

      // run the batches in parallel
      val batchGetFutures = batchGets.map(dynamoDBHelper.batchGetItem)

      val allBatches = Future.sequence(batchGetFutures)

      val allGroups = allBatches.map { batchGetItemResults =>
        batchGetItemResults.flatMap(parseGroups)
      }

      allGroups
    }
  }

  def getAllGroups(): Future[Set[Group]] =
    monitor("repo.Group.getAllGroups") {
      log.info(s"getting all group IDs")
      val scanRequest = new ScanRequest().withTableName(GROUP_TABLE)
      dynamoDBHelper.scanAll(scanRequest).map { results =>
        val startTime = System.currentTimeMillis()
        val groups = results
          .flatMap(_.getItems.asScala.map(fromItem))
          .filter(_.status == GroupStatus.Active)
          .toSet
        val duration = System.currentTimeMillis() - startTime
        log.info(s"trace.getAllGroups; duration = $duration millis")

        groups
      }
    }

  def getGroupByName(groupName: String): Future[Option[Group]] =
    monitor("repo.Group.getGroupByName") {
      log.info(s"Getting group by name $groupName")
      val expressionAttributeValues = new HashMap[String, AttributeValue]
      expressionAttributeValues.put(":name", new AttributeValue(groupName))

      val expressionAttributeNames = new HashMap[String, String]
      expressionAttributeNames.put("#name_attribute", NAME)

      val keyConditionExpression: String = "#name_attribute = :name"

      val queryRequest = new QueryRequest()
        .withTableName(GROUP_TABLE)
        .withIndexName(GROUP_NAME_INDEX)
        .withExpressionAttributeNames(expressionAttributeNames)
        .withExpressionAttributeValues(expressionAttributeValues)
        .withKeyConditionExpression(keyConditionExpression)

      dynamoDBHelper.query(queryRequest).map(firstAvailableGroup)
    }

  /* Filters the results from the query so we don't return Deleted groups */
  private def toAvailableGroups(queryResult: QueryResult): List[Group] =
    queryResult.getItems.asScala.map(fromItem).filter(_.status != GroupStatus.Deleted).toList

  /* Filters the results from the query so we don't return Deleted groups */
  private def firstAvailableGroup(queryResult: QueryResult): Option[Group] =
    toAvailableGroups(queryResult).headOption

  private[repository] def toItem(group: Group) = {
    val item = new java.util.HashMap[String, AttributeValue]()
    item.put(GROUP_ID, new AttributeValue(group.id))
    item.put(NAME, new AttributeValue(group.name))
    item.put(EMAIL, new AttributeValue(group.email))
    item.put(CREATED, new AttributeValue().withN(group.created.getMillis.toString))

    val descAttr =
      group.description.map(new AttributeValue(_)).getOrElse(new AttributeValue().withNULL(true))
    item.put(DESCRIPTION, descAttr)

    item.put(STATUS, new AttributeValue(group.status.toString))
    item.put(MEMBER_IDS, new AttributeValue().withSS(group.memberIds.asJavaCollection))
    item.put(ADMIN_IDS, new AttributeValue().withSS(group.adminUserIds.asJavaCollection))
    item.put(STATUS, new AttributeValue(group.status.toString))
    item
  }

  private[repository] def fromItem(item: java.util.Map[String, AttributeValue]) = {
    val ActiveStatus = "active"
    def groupStatus(str: String): GroupStatus =
      if (str.toLowerCase == ActiveStatus) GroupStatus.Active else GroupStatus.Deleted
    try {
      Group(
        item.get(NAME).getS,
        item.get(EMAIL).getS,
        if (item.get(DESCRIPTION) == null) None else Option(item.get(DESCRIPTION).getS),
        item.get(GROUP_ID).getS,
        new DateTime(item.get(CREATED).getN.toLong),
        groupStatus(item.get(STATUS).getS),
        item.get(MEMBER_IDS).getSS.asScala.toSet,
        item.get(ADMIN_IDS).getSS.asScala.toSet
      )
    } catch {
      case ex: Throwable =>
        log.error("fromItem", ex)
        throw new UnexpectedDynamoResponseException(ex.getMessage, ex)
    }
  }
}
