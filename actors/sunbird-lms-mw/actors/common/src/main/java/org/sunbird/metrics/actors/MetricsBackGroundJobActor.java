package org.sunbird.metrics.actors;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.velocity.VelocityContext;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.FileUtil;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.ProjectUtil.ReportTrackingStatus;
import org.sunbird.common.models.util.azure.CloudService;
import org.sunbird.common.models.util.azure.CloudServiceFactory;
import org.sunbird.common.models.util.mail.SendMail;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.Util;

/** Created by arvind on 28/8/17. */
@ActorConfig(
  tasks = {},
  asyncTasks = {"fileGenerationAndUpload", "processData", "fileGenerationAndUpload"}
)
public class MetricsBackGroundJobActor extends BaseActor {

  private Util.DbInfo reportTrackingdbInfo = Util.dbInfoMap.get(JsonKey.REPORT_TRACKING_DB);
  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, JsonKey.USER);
    ExecutionContext.setRequestId(request.getRequestId());
    String operation = request.getOperation();
    ProjectLogger.log("Operation name is ==" + operation);
    if (operation.equalsIgnoreCase(ActorOperations.PROCESS_DATA.getValue())) {
      processData(request);
    } else if (operation.equalsIgnoreCase(ActorOperations.FILE_GENERATION_AND_UPLOAD.getValue())) {
      fileGenerationAndUpload(request);
    } else if (operation.equalsIgnoreCase(ActorOperations.SEND_MAIL.getValue())) {
      sendMail(request);
    } else {
      onReceiveUnsupportedOperation(request.getOperation());
    }
  }

  private void processData(Request actorMessage) {
    ProjectLogger.log("In processData for metrics report");
    String operation = (String) actorMessage.getRequest().get(JsonKey.REQUEST);
    String requestId = (String) actorMessage.getRequest().get(JsonKey.REQUEST_ID);
    Request metricsRequest = new Request();
    Map<String, Object> request = new HashMap<>();
    request.put(JsonKey.REQUEST_ID, requestId);
    if (JsonKey.OrgCreation.equalsIgnoreCase(operation)) {
      metricsRequest.setOperation(ActorOperations.ORG_CREATION_METRICS_DATA.getValue());
      metricsRequest.setRequest(request);
      tellToAnother(metricsRequest);
    } else if (JsonKey.OrgConsumption.equalsIgnoreCase(operation)) {
      metricsRequest.setOperation(ActorOperations.ORG_CONSUMPTION_METRICS_DATA.getValue());
      metricsRequest.setRequest(request);
      tellToAnother(metricsRequest);
    } else if (JsonKey.CourseProgress.equalsIgnoreCase(operation)) {
      metricsRequest.setOperation(ActorOperations.COURSE_PROGRESS_METRICS_DATA.getValue());
      metricsRequest.setRequest(request);
      tellToAnother(metricsRequest);
    }
  }

  @SuppressWarnings("unchecked")
  private void sendMail(Request request) {
    ProjectLogger.log("In sendMail for metrics Report");
    Map<String, Object> map = request.getRequest();
    SimpleDateFormat simpleDateFormat = ProjectUtil.getDateFormatter();
    simpleDateFormat.setLenient(false);
    String requestId = (String) map.get(JsonKey.REQUEST_ID);

    // fetch the DB details from database on basis of requestId ....
    Response response =
        cassandraOperation.getRecordById(
            reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), requestId);
    List<Map<String, Object>> responseList =
        (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (responseList.isEmpty()) {
      ProjectLogger.log("Invalid data");
      throw new ProjectCommonException(
          ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    Map<String, Object> reportDbInfo = responseList.get(0);
    Map<String, Object> dbReqMap = new HashMap<>();
    dbReqMap.put(JsonKey.ID, requestId);

    if (processMailSending(reportDbInfo)) {
      dbReqMap.put(JsonKey.STATUS, ReportTrackingStatus.SENDING_MAIL_SUCCESS.getValue());
      dbReqMap.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
      cassandraOperation.updateRecord(
          reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), dbReqMap);
    } else {
      increasetryCount(reportDbInfo);
      if ((Integer) reportDbInfo.get(JsonKey.TRY_COUNT) > 3) {
        dbReqMap.put(JsonKey.STATUS, ReportTrackingStatus.FAILED.getValue());
        dbReqMap.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
        cassandraOperation.updateRecord(
            reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), dbReqMap);
      } else {
        dbReqMap.put(JsonKey.STATUS, ReportTrackingStatus.SENDING_MAIL.getValue());
        dbReqMap.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
        cassandraOperation.updateRecord(
            reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), dbReqMap);
      }
    }
  }

  private void increasetryCount(Map<String, Object> map) {
    if (null == map.get(JsonKey.TRY_COUNT)) {
      map.put(JsonKey.TRY_COUNT, 0);
    } else {
      Integer tryCount = (Integer) map.get(JsonKey.TRY_COUNT);
      map.put(JsonKey.TRY_COUNT, tryCount + 1);
    }
  }

  @SuppressWarnings("unchecked")
  private void fileGenerationAndUpload(Request request) throws IOException {
    ProjectLogger.log("In fileGeneration and Upload");
    Map<String, Object> map = request.getRequest();
    String requestId = (String) map.get(JsonKey.REQUEST_ID);
    SimpleDateFormat simpleDateFormat = ProjectUtil.getDateFormatter();
    simpleDateFormat.setLenient(false);

    Response response =
        cassandraOperation.getRecordById(
            reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), requestId);
    List<Map<String, Object>> responseList =
        (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (responseList.isEmpty()) {
      ProjectLogger.log("Invalid data");
      throw new ProjectCommonException(
          ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
    Map<String, Object> reportDbInfo = responseList.get(0);
    String fileFormat = (String) reportDbInfo.get(JsonKey.FORMAT);
    FileUtil fileUtil = FileUtil.getFileUtil(fileFormat);

    Map<String, Object> dbReqMap = new HashMap<>();
    dbReqMap.put(JsonKey.ID, requestId);

    List<List<Object>> finalList = (List<List<Object>>) map.get(JsonKey.DATA);
    String fileName = (String) map.get(JsonKey.FILE_NAME);
    if (StringUtils.isBlank(fileName)) {
      fileName = "File-" + requestId;
    }
    File file = null;
    try {
      file = fileUtil.writeToFile(fileName, finalList);
    } catch (Exception ex) {
      ProjectLogger.log("PROCESS FAILED WHILE CONVERTING THE DATA TO FILE .", ex);
      // update DB as status failed since unable to convert data to file
      dbReqMap.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
      dbReqMap.put(JsonKey.STATUS, ReportTrackingStatus.FAILED.getValue());
      cassandraOperation.updateRecord(
          reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), dbReqMap);
      throw ex;
    }

    String storageUrl = null;
    try {
      // TODO : confirm the container name ...
      storageUrl = processFileUpload(file, "testContainer");

    } catch (Exception e) {
      ProjectLogger.log(
          "Error occurred while uploading file on storage for requset " + requestId, e);
      increasetryCount(reportDbInfo);
      if ((Integer) reportDbInfo.get(JsonKey.TRY_COUNT) > 3) {
        dbReqMap.put(JsonKey.STATUS, ReportTrackingStatus.FAILED.getValue());
        dbReqMap.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
        cassandraOperation.updateRecord(
            reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), dbReqMap);
      } else {
        dbReqMap.put(JsonKey.STATUS, ReportTrackingStatus.UPLOADING_FILE.getValue());
        dbReqMap.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
        cassandraOperation.updateRecord(
            reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), dbReqMap);
      }
      throw e;
    } finally {
      if (ProjectUtil.isNotNull(file)) {
        file.delete();
      }
    }

    reportDbInfo.put(JsonKey.FILE_URL, storageUrl);
    dbReqMap.put(JsonKey.FILE_URL, storageUrl);
    dbReqMap.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
    dbReqMap.put(JsonKey.DATA, null);
    dbReqMap.put(JsonKey.STATUS, ReportTrackingStatus.UPLOADING_FILE_SUCCESS.getValue());
    cassandraOperation.updateRecord(
        reportTrackingdbInfo.getKeySpace(), reportTrackingdbInfo.getTableName(), dbReqMap);

    Request backGroundRequest = new Request();
    backGroundRequest.setOperation(ActorOperations.SEND_MAIL.getValue());

    Map<String, Object> innerMap = new HashMap<>();
    innerMap.put(JsonKey.REQUEST_ID, requestId);

    backGroundRequest.setRequest(innerMap);
    self().tell(backGroundRequest, self());
  }

  private boolean processMailSending(Map<String, Object> reportDbInfo) {

    Map<String, Object> templateMap = new HashMap<>();
    templateMap.put(JsonKey.ACTION_URL, reportDbInfo.get(JsonKey.FILE_URL));
    templateMap.put(JsonKey.NAME, reportDbInfo.get(JsonKey.FIRST_NAME));
    String resource = getReportResourceName(reportDbInfo);
    templateMap.put(
        JsonKey.BODY,
        "Please Find Attached Report for "
            + resource
            + " for the Period  "
            + getPeriod((String) reportDbInfo.get(JsonKey.PERIOD))
            + " as requested on : "
            + reportDbInfo.get(JsonKey.CREATED_DATE));
    templateMap.put(JsonKey.ACTION_NAME, "DOWNLOAD REPORT");
    VelocityContext context = ProjectUtil.getContext(templateMap);

    return SendMail.sendMail(
        new String[] {(String) reportDbInfo.get(JsonKey.EMAIL)},
        reportDbInfo.get(JsonKey.TYPE) + " for " + resource,
        context,
        ProjectUtil.getTemplate(Collections.emptyMap()));
  }

  private String getReportResourceName(Map<String, Object> reportDbInfo) {

    String resource = (String) reportDbInfo.get(JsonKey.RESOURCE_NAME);
    if (StringUtils.isEmpty(resource)) {
      resource = (String) reportDbInfo.get(JsonKey.RESOURCE_ID);
    }
    return resource;
  }

  private String getPeriod(String period) {
    if ("7d".equalsIgnoreCase(period)) {
      return "7 Days";
    } else if ("14d".equalsIgnoreCase(period)) {
      return "14 Days";
    } else if ("5w".equalsIgnoreCase(period)) {
      return "5 Weeks";
    } else if ("fromBegining".equalsIgnoreCase(period)) {
      return "from Beginning";
    } else {
      return "";
    }
  }

  private String processFileUpload(File file, String container) {

    String storageUrl = null;
    try {
      CloudService service = (CloudService) CloudServiceFactory.get("Azure");
      if (null == service) {
        ProjectLogger.log("The cloud service is not available");
        throw new ProjectCommonException(
            ResponseCode.invalidRequestData.getErrorCode(),
            ResponseCode.invalidRequestData.getErrorMessage(),
            ResponseCode.CLIENT_ERROR.getResponseCode());
      }
      storageUrl = service.uploadFile(container, file);
    } catch (Exception e) {
      ProjectLogger.log("Exception Occurred while reading file in FileUploadServiceActor", e);
      throw e;
    }
    return storageUrl;
  }
}
