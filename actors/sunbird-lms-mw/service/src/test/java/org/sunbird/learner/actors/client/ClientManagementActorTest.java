package org.sunbird.learner.actors.client;

import static akka.testkit.JavaTestKit.duration;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.javadsl.TestKit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.junit.runners.MethodSorters;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseMessage;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.Util;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Ignore
public class ClientManagementActorTest {

  private static ActorSystem system;
  private static final Props props = Props.create(ClientManagementActor.class);
  private static String masterKey = "";
  private static String clientId = "";

  @BeforeClass
  public static void setUp() {
    system = ActorSystem.create("system");
    Util.checkCassandraDbConnections(JsonKey.SUNBIRD);
  }

  @SuppressWarnings("deprecation")
  @Test
  public void test1RegisterClientSuccess() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.CLIENT_NAME, "Test");
    actorMessage.setRequest(request);
    actorMessage.setOperation(ActorOperations.REGISTER_CLIENT.getValue());

    subject.tell(actorMessage, probe.getRef());
    Response res = probe.expectMsgClass(duration("10 second"), Response.class);
    clientId = (String) res.getResult().get(JsonKey.CLIENT_ID);
    masterKey = (String) res.getResult().get(JsonKey.MASTER_KEY);
    Assert.assertTrue(!StringUtils.isBlank(clientId));
    Assert.assertTrue(!StringUtils.isBlank(masterKey));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void test2RegisterClientException() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.CLIENT_NAME, "Test");
    actorMessage.setRequest(request);
    actorMessage.setOperation(ActorOperations.REGISTER_CLIENT.getValue());

    subject.tell(actorMessage, probe.getRef());
    ProjectCommonException res =
        probe.expectMsgClass(duration("10 second"), ProjectCommonException.class);
    if (null != res) {
      Assert.assertEquals(ResponseMessage.Message.INVALID_CLIENT_NAME, res.getMessage());
    }
  }

  @SuppressWarnings({"deprecation", "unchecked"})
  @Test
  public void test3GetClientKeySuccess() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.CLIENT_ID, clientId);
    request.put(JsonKey.TYPE, JsonKey.CLIENT_ID);
    actorMessage.setRequest(request);
    actorMessage.setOperation(ActorOperations.GET_CLIENT_KEY.getValue());

    subject.tell(actorMessage, probe.getRef());
    Response res = probe.expectMsgClass(duration("20 second"), Response.class);
    List<Map<String, Object>> dataList =
        (List<Map<String, Object>>) res.getResult().get(JsonKey.RESPONSE);
    Assert.assertEquals(clientId, dataList.get(0).get(JsonKey.ID));
    Assert.assertEquals(masterKey, dataList.get(0).get(JsonKey.MASTER_KEY));
    Assert.assertEquals("test", dataList.get(0).get(JsonKey.CLIENT_NAME));
    Assert.assertTrue(!StringUtils.isBlank((String) dataList.get(0).get(JsonKey.CREATED_DATE)));
    Assert.assertTrue(!StringUtils.isBlank((String) dataList.get(0).get(JsonKey.UPDATED_DATE)));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void test4GetClientKeyFailure() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.CLIENT_ID, "test123");
    actorMessage.setRequest(request);
    actorMessage.setOperation(ActorOperations.GET_CLIENT_KEY.getValue());

    subject.tell(actorMessage, probe.getRef());
    ProjectCommonException res =
        probe.expectMsgClass(duration("10 second"), ProjectCommonException.class);
    if (null != res) {
      Assert.assertEquals(ResponseMessage.Message.INVALID_REQUESTED_DATA, res.getMessage());
    }
  }

  @SuppressWarnings("deprecation")
  @Test
  public void test5UpdateClientKeySuccess() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.CLIENT_ID, clientId);
    request.put(JsonKey.MASTER_KEY, masterKey);
    actorMessage.setRequest(request);
    actorMessage.setOperation(ActorOperations.UPDATE_CLIENT_KEY.getValue());

    subject.tell(actorMessage, probe.getRef());
    Response res = probe.expectMsgClass(duration("20 second"), Response.class);
    Assert.assertEquals(clientId, res.getResult().get(JsonKey.CLIENT_ID));
    Assert.assertNotEquals(masterKey, res.getResult().get(JsonKey.MASTER_KEY));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void test6UpdateClientKeyFailure() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.CLIENT_ID, "test");
    request.put(JsonKey.MASTER_KEY, masterKey);
    actorMessage.setRequest(request);
    actorMessage.setOperation(ActorOperations.UPDATE_CLIENT_KEY.getValue());

    subject.tell(actorMessage, probe.getRef());
    ProjectCommonException res =
        probe.expectMsgClass(duration("10 second"), ProjectCommonException.class);
    if (null != res) {
      Assert.assertEquals(ResponseMessage.Message.INVALID_REQUESTED_DATA, res.getMessage());
    }
  }

  @SuppressWarnings("deprecation")
  @Test
  public void test7UpdateClientKeyFailureInvalidKey() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.CLIENT_ID, clientId);
    request.put(JsonKey.MASTER_KEY, "test");
    actorMessage.setRequest(request);
    actorMessage.setOperation(ActorOperations.UPDATE_CLIENT_KEY.getValue());

    subject.tell(actorMessage, probe.getRef());
    ProjectCommonException res =
        probe.expectMsgClass(duration("10 second"), ProjectCommonException.class);
    if (null != res) {
      Assert.assertEquals(ResponseMessage.Message.INVALID_REQUESTED_DATA, res.getMessage());
    }
  }

  @AfterClass
  public static void destroy() {
    CassandraOperation operation = ServiceFactory.getInstance();
    Util.DbInfo clientInfoDB = Util.dbInfoMap.get(JsonKey.CLIENT_INFO_DB);
    // Delete client data from cassandra
    operation.deleteRecord(clientInfoDB.getKeySpace(), clientInfoDB.getTableName(), clientId);
  }
}
