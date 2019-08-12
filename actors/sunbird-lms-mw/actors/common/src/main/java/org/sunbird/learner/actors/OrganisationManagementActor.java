package org.sunbird.learner.actors;

import static org.sunbird.learner.util.Util.isNotNull;
import static org.sunbird.learner.util.Util.isNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.ElasticSearchUtil;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.EmailValidator;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LocationActorOperation;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.ProjectUtil.EsIndex;
import org.sunbird.common.models.util.ProjectUtil.EsType;
import org.sunbird.common.models.util.Slug;
import org.sunbird.common.models.util.datasecurity.EncryptionService;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.common.responsecode.ResponseMessage;
import org.sunbird.dto.SearchDTO;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.DataCacheHandler;
import org.sunbird.learner.util.Util;
import org.sunbird.models.organisation.Organisation;
import org.sunbird.telemetry.util.TelemetryUtil;
import org.sunbird.validator.location.LocationRequestValidator;

/**
 * This actor will handle organisation related operation .
 *
 * @author Amit Kumar
 * @author Arvind
 */
@ActorConfig(
  tasks = {
    "createOrg",
    "updateOrg",
    "updateOrgStatus",
    "getOrgDetails",
    "addMemberOrganisation",
    "removeMemberOrganisation",
    "getOrgTypeList",
    "createOrgType",
    "updateOrgType"
  },
  asyncTasks = {}
)
public class OrganisationManagementActor extends BaseActor {
  private ObjectMapper mapper = new ObjectMapper();
  private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private static final LocationRequestValidator validator = new LocationRequestValidator();
  private final EncryptionService encryptionService =
      org.sunbird.common.models.util.datasecurity.impl.ServiceFactory.getEncryptionServiceInstance(
          null);

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, JsonKey.ORGANISATION);
    // set request id fto thread loacl...
    ExecutionContext.setRequestId(request.getRequestId());
    if (request.getOperation().equalsIgnoreCase(ActorOperations.CREATE_ORG.getValue())) {
      createOrg(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.UPDATE_ORG_STATUS.getValue())) {
      updateOrgStatus(request);
    } else if (request.getOperation().equalsIgnoreCase(ActorOperations.UPDATE_ORG.getValue())) {
      updateOrgData(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.GET_ORG_DETAILS.getValue())) {
      getOrgDetails(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.ADD_MEMBER_ORGANISATION.getValue())) {
      addMemberOrganisation(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.REMOVE_MEMBER_ORGANISATION.getValue())) {
      removeMemberOrganisation(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.GET_ORG_TYPE_LIST.getValue())) {
      getOrgTypeList();
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.CREATE_ORG_TYPE.getValue())) {
      createOrgType(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.UPDATE_ORG_TYPE.getValue())) {
      updateOrgType(request);
    } else {
      onReceiveUnsupportedOperation(request.getOperation());
    }
  }

  private void updateOrgType(Request actorMessage) {
    ProjectLogger.log("updateOrgType method call start");
    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    try {
      Util.DbInfo orgTypeDbInfo = Util.dbInfoMap.get(JsonKey.ORG_TYPE_DB);
      Map<String, Object> request = actorMessage.getRequest();
      Response result =
          cassandraOperation.getRecordsByProperty(
              orgTypeDbInfo.getKeySpace(),
              orgTypeDbInfo.getTableName(),
              JsonKey.NAME,
              request.get(JsonKey.NAME));
      List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
      if (!(list.isEmpty())) {
        Map<String, Object> map = list.get(0);
        if (!(((String) map.get(JsonKey.ID)).equals(request.get(JsonKey.ID)))) {
          ProjectCommonException exception =
              new ProjectCommonException(
                  ResponseCode.orgTypeAlreadyExist.getErrorCode(),
                  ResponseCode.orgTypeAlreadyExist.getErrorMessage(),
                  ResponseCode.CLIENT_ERROR.getResponseCode());
          sender().tell(exception, self());
          return;
        }
      }

      request.put(JsonKey.UPDATED_DATE, ProjectUtil.getFormattedDate());
      Response response =
          cassandraOperation.updateRecord(
              orgTypeDbInfo.getKeySpace(), orgTypeDbInfo.getTableName(), request);
      sender().tell(response, self());

      targetObject =
          TelemetryUtil.generateTargetObject(
              (String) request.get(JsonKey.ID), "organisationType", JsonKey.UPDATE, null);
      TelemetryUtil.telemetryProcessingCall(
          actorMessage.getRequest(), targetObject, correlatedObject);
      // update DataCacheHandler orgType map with new data
      new Thread() {
        @Override
        public void run() {
          if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
            DataCacheHandler.getOrgTypeMap()
                .put(
                    ((String) request.get(JsonKey.NAME)).toLowerCase(),
                    (String) request.get(JsonKey.ID));
          }
        }
      }.start();
    } catch (Exception e) {
      ProjectLogger.log("Exception Occurred while updating data to orgType table :: ", e);
      sender().tell(e, self());
    }
  }

  private void createOrgType(Request actorMessage) {

    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    ProjectLogger.log("createOrgType method call start");
    try {
      Util.DbInfo orgTypeDbInfo = Util.dbInfoMap.get(JsonKey.ORG_TYPE_DB);
      Map<String, Object> request = actorMessage.getRequest();
      Response result =
          cassandraOperation.getAllRecords(
              orgTypeDbInfo.getKeySpace(), orgTypeDbInfo.getTableName());
      List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
      if (!(list.isEmpty())) {
        for (Map<String, Object> map : list) {
          if (((String) map.get(JsonKey.NAME))
              .equalsIgnoreCase((String) request.get(JsonKey.NAME))) {
            ProjectCommonException exception =
                new ProjectCommonException(
                    ResponseCode.orgTypeAlreadyExist.getErrorCode(),
                    ResponseCode.orgTypeAlreadyExist.getErrorMessage(),
                    ResponseCode.CLIENT_ERROR.getResponseCode());
            sender().tell(exception, self());
            return;
          }
        }
      }
      request.put(JsonKey.CREATED_DATE, ProjectUtil.getFormattedDate());
      String id = ProjectUtil.getUniqueIdFromTimestamp(actorMessage.getEnv());
      request.put(JsonKey.ID, id);
      Response response =
          cassandraOperation.insertRecord(
              orgTypeDbInfo.getKeySpace(), orgTypeDbInfo.getTableName(), request);
      sender().tell(response, self());

      targetObject =
          TelemetryUtil.generateTargetObject(id, "organisationType", JsonKey.CREATE, null);
      TelemetryUtil.telemetryProcessingCall(
          actorMessage.getRequest(), targetObject, correlatedObject);

      // update DataCacheHandler orgType map with new data
      new Thread() {
        @Override
        public void run() {
          if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
            DataCacheHandler.getOrgTypeMap()
                .put(
                    ((String) request.get(JsonKey.NAME)).toLowerCase(),
                    (String) request.get(JsonKey.ID));
          }
        }
      }.start();
    } catch (Exception e) {
      ProjectLogger.log("Exception Occurred while inserting data to orgType table :: ", e);
      sender().tell(e, self());
    }
  }

  private void getOrgTypeList() {
    ProjectLogger.log("getOrgTypeList method call start");
    try {
      Util.DbInfo orgTypeDbInfo = Util.dbInfoMap.get(JsonKey.ORG_TYPE_DB);
      Response response =
          cassandraOperation.getAllRecords(
              orgTypeDbInfo.getKeySpace(), orgTypeDbInfo.getTableName());
      List<Map<String, Object>> list = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (!(list.isEmpty())) {
        for (Map<String, Object> map : list) {
          map.remove(JsonKey.CREATED_BY);
          map.remove(JsonKey.CREATED_DATE);
          map.remove(JsonKey.UPDATED_BY);
          map.remove(JsonKey.UPDATED_DATE);
        }
      }
      sender().tell(response, self());
    } catch (Exception e) {
      ProjectLogger.log("Exception Occurred while fetching orgType List :: ", e);
      sender().tell(e, self());
    }
  }

  /** Method to create an Organisation . */
  @SuppressWarnings("unchecked")
  private void createOrg(Request actorMessage) {
    ProjectLogger.log("Create org method call start");
    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    try {
      actorMessage.toLower();
      Map<String, Object> request = actorMessage.getRequest();
      if (request.containsKey(JsonKey.LOCATION_CODE)
          && !CollectionUtils.isEmpty((List<String>) request.get(JsonKey.LOCATION_CODE))) {
        validateCodeAndAddLocationIds(request);
      }
      if (request.containsKey(JsonKey.ORG_TYPE)
          && !StringUtils.isBlank((String) request.get(JsonKey.ORG_TYPE))) {
        request.put(JsonKey.ORG_TYPE_ID, validateOrgType((String) request.get(JsonKey.ORG_TYPE)));
      }

      if (request.containsKey(JsonKey.LOC_ID)
          && !StringUtils.isBlank((String) request.get(JsonKey.LOC_ID))) {
        request.put(JsonKey.LOC_ID, validateLocationId((String) request.get(JsonKey.LOC_ID)));
      }

      Map<String, Object> addressReq = null;
      if (null != request.get(JsonKey.ADDRESS)) {
        addressReq = (Map<String, Object>) request.get(JsonKey.ADDRESS);
        request.remove(JsonKey.ADDRESS);
      }
      Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);

      validateChannelIdForRootOrg(request);
      if (request.containsKey(JsonKey.CHANNEL)) {
        validateChannel(request);
      }

      String relation = (String) request.get(JsonKey.RELATION);
      request.remove(JsonKey.RELATION);

      String parentOrg = (String) request.get(JsonKey.PARENT_ORG_ID);

      Boolean isValidParent = false;
      if (!StringUtils.isBlank(parentOrg) && isValidParent(parentOrg)) {
        validateRootOrg(request);
        isValidParent = true;
      }
      String updatedBy = (String) actorMessage.getRequest().get(JsonKey.REQUESTED_BY);
      if (!(StringUtils.isBlank(updatedBy))) {
        request.put(JsonKey.CREATED_BY, updatedBy);
      }
      String uniqueId = ProjectUtil.getUniqueIdFromTimestamp(actorMessage.getEnv());
      request.put(JsonKey.ID, uniqueId);
      request.put(JsonKey.CREATED_DATE, ProjectUtil.getFormattedDate());
      request.put(JsonKey.STATUS, ProjectUtil.OrgStatus.ACTIVE.getValue());
      // removing default from request, not allowing user to create default org.
      request.remove(JsonKey.IS_DEFAULT);
      // allow lower case values for source and externalId to the database
      if (request.get(JsonKey.PROVIDER) != null) {
        request.put(JsonKey.PROVIDER, ((String) request.get(JsonKey.PROVIDER)).toLowerCase());
      }
      String externalId = (String) request.get(JsonKey.EXTERNAL_ID);
      if (externalId != null) {
        if (!validateExternalIdUniqueness(externalId.toLowerCase(), null)) {
          ProjectCommonException.throwClientErrorException(
              ResponseCode.errorDuplicateEntry,
              MessageFormat.format(
                  ResponseCode.errorDuplicateEntry.getErrorMessage(),
                  externalId,
                  JsonKey.EXTERNAL_ID));
        }
        request.put(JsonKey.EXTERNAL_ID, externalId);
      }
      // update address if present in request
      if (null != addressReq && addressReq.size() > 0) {
        String addressId = ProjectUtil.getUniqueIdFromTimestamp(actorMessage.getEnv());
        addressReq.put(JsonKey.ID, addressId);
        addressReq.put(JsonKey.CREATED_DATE, ProjectUtil.getFormattedDate());

        if (!(StringUtils.isBlank(updatedBy))) {
          addressReq.put(JsonKey.CREATED_BY, updatedBy);
        }
        upsertAddress(addressReq);
        request.put(JsonKey.ADDRESS_ID, addressId);
        telemetryGenerationForOrgAddress(addressReq, request, false);
      }

      Boolean isRootOrg = (Boolean) request.get(JsonKey.IS_ROOT_ORG);
      // set root org on basis of whether the org itself is root org or not ...
      if (null != isRootOrg && isRootOrg) {
        request.put(JsonKey.ROOT_ORG_ID, uniqueId);
      } else if (StringUtils.isBlank((String) request.get(JsonKey.ROOT_ORG_ID))) {
        request.put(JsonKey.ROOT_ORG_ID, JsonKey.DEFAULT_ROOT_ORG_ID);
      }

      if (request.containsKey(JsonKey.EMAIL)
          && !EmailValidator.isEmailValid((String) request.get(JsonKey.EMAIL))) {
        ProjectCommonException.throwClientErrorException(ResponseCode.emailFormatError);
      }

      // adding one extra filed for tag.
      if (!StringUtils.isBlank(((String) request.get(JsonKey.HASHTAGID)))) {
        request.put(
            JsonKey.HASHTAGID,
            validateHashTagId(((String) request.get(JsonKey.HASHTAGID)), JsonKey.CREATE, ""));
      } else {
        request.put(JsonKey.HASHTAGID, uniqueId);
      }

      // if channel is available then make slug for channel.
      // remove the slug key if coming from user input
      request.remove(JsonKey.SLUG);
      if (request.containsKey(JsonKey.CHANNEL)) {
        String slug = Slug.makeSlug((String) request.getOrDefault(JsonKey.CHANNEL, ""), true);
        if (null != isRootOrg && isRootOrg) {
          boolean bool = isSlugUnique(slug);
          if (bool) {
            request.put(JsonKey.SLUG, slug);
          } else {
            ProjectCommonException exception =
                new ProjectCommonException(
                    ResponseCode.slugIsNotUnique.getErrorCode(),
                    ResponseCode.slugIsNotUnique.getErrorMessage(),
                    ResponseCode.CLIENT_ERROR.getResponseCode());
            sender().tell(exception, self());
            return;
          }
        } else {
          request.put(JsonKey.SLUG, slug);
        }
      }
      // check contactDetail filed is coming or not. it will always come as list of
      // map
      List<Map<String, Object>> listOfMap = null;
      if (request.containsKey(JsonKey.CONTACT_DETAILS)) {
        listOfMap = (List<Map<String, Object>>) request.get(JsonKey.CONTACT_DETAILS);
        if (listOfMap != null && !listOfMap.isEmpty()) {
          try {
            request.put(JsonKey.CONTACT_DETAILS, mapper.writeValueAsString(listOfMap));
          } catch (IOException e) {
            ProjectLogger.log(e.getMessage(), e);
          }
        }
      }

      if (null != isRootOrg && isRootOrg) {
        boolean bool = Util.registerChannel(request);
        if (!bool) {
          ProjectCommonException exception =
              new ProjectCommonException(
                  ResponseCode.channelRegFailed.getErrorCode(),
                  ResponseCode.channelRegFailed.getErrorMessage(),
                  ResponseCode.SERVER_ERROR.getResponseCode());
          sender().tell(exception, self());
          return;
        }
      } else {
        request.put(JsonKey.IS_ROOT_ORG, false);
      }

      // This will remove all extra unnecessary parameter from request
      Organisation org = mapper.convertValue(request, Organisation.class);
      request = mapper.convertValue(org, Map.class);
      Response result =
          cassandraOperation.insertRecord(
              orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), request);
      ProjectLogger.log("Org data saved into cassandra.");
      // create org_map if parentOrgId is present in request
      if (isValidParent) {
        upsertOrgMap(
            uniqueId,
            parentOrg,
            (String) request.get(JsonKey.ROOT_ORG_ID),
            actorMessage.getEnv(),
            relation);
      }

      ProjectLogger.log("Created org id is ----." + uniqueId);
      result.getResult().put(JsonKey.ORGANISATION_ID, uniqueId);
      sender().tell(result, self());

      targetObject =
          TelemetryUtil.generateTargetObject(uniqueId, JsonKey.ORGANISATION, JsonKey.CREATE, null);
      TelemetryUtil.generateCorrelatedObject(
          uniqueId, JsonKey.ORGANISATION, null, correlatedObject);
      TelemetryUtil.telemetryProcessingCall(
          (Map<String, Object>) actorMessage.getRequest().get(JsonKey.ORGANISATION),
          targetObject,
          correlatedObject);
      if (null != addressReq) {
        request.put(JsonKey.ADDRESS, addressReq);
      }
      if (listOfMap != null) {
        request.put(JsonKey.CONTACT_DETAILS, listOfMap);
      } else {
        listOfMap = new ArrayList<>();
        request.put(JsonKey.CONTACT_DETAILS, listOfMap);
      }
      Request orgReq = new Request();
      orgReq.getRequest().put(JsonKey.ORGANISATION, request);
      orgReq.setOperation(ActorOperations.INSERT_ORG_INFO_ELASTIC.getValue());
      ProjectLogger.log("Calling background job to save org data into ES" + uniqueId);
      tellToAnother(orgReq);
    } catch (ProjectCommonException e) {
      ProjectLogger.log("Some error occurs" + e.getMessage());
      sender().tell(e, self());
      return;
    }
  }

  private void validateCodeAndAddLocationIds(Map<String, Object> req) {
    List<String> locationIdList =
        validator.getValidatedLocationIds(
            getActorRef(LocationActorOperation.SEARCH_LOCATION.getValue()),
            (List<String>) req.get(JsonKey.LOCATION_CODE));
    req.put(JsonKey.LOCATION_IDS, locationIdList);
    req.remove(JsonKey.LOCATION_CODE);
  }

  private String validateHashTagId(String hashTagId, String opType, String orgId) {
    Map<String, Object> filters = new HashMap<>();
    filters.put(JsonKey.HASHTAGID, hashTagId);
    SearchDTO searchDto = new SearchDTO();
    searchDto.getAdditionalProperties().put(JsonKey.FILTERS, filters);
    Map<String, Object> result =
        ElasticSearchUtil.complexSearch(
            searchDto,
            ProjectUtil.EsIndex.sunbird.getIndexName(),
            ProjectUtil.EsType.organisation.getTypeName());
    List<Map<String, Object>> dataMapList = (List<Map<String, Object>>) result.get(JsonKey.CONTENT);
    if (opType.equalsIgnoreCase(JsonKey.CREATE)) {
      if (!dataMapList.isEmpty()) {
        throw new ProjectCommonException(
            ResponseCode.invalidHashTagId.getErrorCode(),
            ResponseCode.invalidHashTagId.getErrorMessage(),
            ResponseCode.CLIENT_ERROR.getResponseCode());
      }
    } else if (opType.equalsIgnoreCase(JsonKey.UPDATE) && !dataMapList.isEmpty()) {
      Map<String, Object> orgMap = dataMapList.get(0);
      if (!(((String) orgMap.get(JsonKey.ID)).equalsIgnoreCase(orgId))) {
        throw new ProjectCommonException(
            ResponseCode.invalidHashTagId.getErrorCode(),
            ResponseCode.invalidHashTagId.getErrorMessage(),
            ResponseCode.CLIENT_ERROR.getResponseCode());
      }
    }
    return hashTagId;
  }

  private String validateLocationId(String locId) {
    Util.DbInfo geoLocDbInfo = Util.dbInfoMap.get(JsonKey.GEO_LOCATION_DB);
    Response response =
        cassandraOperation.getRecordById(
            geoLocDbInfo.getKeySpace(), geoLocDbInfo.getTableName(), locId);
    List<Map<String, Object>> list = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (!list.isEmpty()) {
      Map<String, Object> map = list.get(0);
      return ((String) map.get(JsonKey.ID));
    } else {
      throw new ProjectCommonException(
          ResponseCode.invalidLocationId.getErrorCode(),
          ResponseCode.invalidLocationId.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
  }

  private String validateOrgType(String orgType) {
    String orgTypeId = null;
    if (!StringUtils.isBlank(DataCacheHandler.getOrgTypeMap().get(orgType.toLowerCase()))) {
      orgTypeId = DataCacheHandler.getOrgTypeMap().get(orgType.toLowerCase());
    } else {
      Util.DbInfo orgTypeDbInfo = Util.dbInfoMap.get(JsonKey.ORG_TYPE_DB);
      Response response =
          cassandraOperation.getAllRecords(
              orgTypeDbInfo.getKeySpace(), orgTypeDbInfo.getTableName());
      List<Map<String, Object>> list = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (!list.isEmpty()) {
        for (Map<String, Object> map : list) {
          if ((((String) map.get(JsonKey.NAME)).toLowerCase())
              .equalsIgnoreCase(orgType.toLowerCase())) {
            orgTypeId = (String) map.get(JsonKey.ID);
            DataCacheHandler.getOrgTypeMap()
                .put(((String) map.get(JsonKey.NAME)).toLowerCase(), (String) map.get(JsonKey.ID));
          }
        }
      }
      if (null == orgTypeId) {
        throw new ProjectCommonException(
            ResponseCode.invalidOrgType.getErrorCode(),
            ResponseCode.invalidOrgType.getErrorMessage(),
            ResponseCode.CLIENT_ERROR.getResponseCode());
      }
    }
    return orgTypeId;
  }

  /** Updates the status of the Organisation */
  @SuppressWarnings("unchecked")
  private void updateOrgStatus(Request actorMessage) {

    // object of telemetry event...
    Map<String, Object> targetObject = null;

    try {
      actorMessage.toLower();
      Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
      Map<String, Object> request = actorMessage.getRequest();
      if (!(validateOrgRequest(request))) {
        ProjectLogger.log("REQUESTED DATA IS NOT VALID");
        return;
      }
      Map<String, Object> orgDBO;
      Map<String, Object> updateOrgDBO = new HashMap<>();
      String updatedBy = (String) request.get(JsonKey.REQUESTED_BY);

      String orgId = (String) request.get(JsonKey.ORGANISATION_ID);
      Response result =
          cassandraOperation.getRecordById(
              orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), orgId);
      List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
      if (!(list.isEmpty())) {
        orgDBO = list.get(0);
      } else {
        ProjectLogger.log("Invalid Org Id");
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.invalidRequestData.getErrorCode(),
                ResponseCode.invalidRequestData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }

      Integer currentStatus = (Integer) orgDBO.get(JsonKey.STATUS);
      Integer nextStatus = ((BigInteger) request.get(JsonKey.STATUS)).intValue();
      if (!(Util.checkOrgStatusTransition(currentStatus, nextStatus))) {

        ProjectLogger.log("Invalid Org State transation");
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.invalidRequestData.getErrorCode(),
                ResponseCode.invalidRequestData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }

      if (!(StringUtils.isBlank(updatedBy))) {
        updateOrgDBO.put(JsonKey.UPDATED_BY, updatedBy);
      }
      updateOrgDBO.put(JsonKey.UPDATED_DATE, ProjectUtil.getFormattedDate());
      updateOrgDBO.put(JsonKey.ID, orgDBO.get(JsonKey.ID));
      updateOrgDBO.put(JsonKey.STATUS, nextStatus);

      Response response =
          cassandraOperation.updateRecord(
              orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), updateOrgDBO);
      response.getResult().put(JsonKey.ORGANISATION_ID, orgDBO.get(JsonKey.ID));
      sender().tell(response, self());

      // update the ES --
      Request orgRequest = new Request();
      orgRequest.getRequest().put(JsonKey.ORGANISATION, updateOrgDBO);
      orgRequest.setOperation(ActorOperations.UPDATE_ORG_INFO_ELASTIC.getValue());
      tellToAnother(orgRequest);

      targetObject =
          TelemetryUtil.generateTargetObject(orgId, JsonKey.ORGANISATION, JsonKey.UPDATE, null);
      Map<String, Object> telemetryAction = new HashMap<>();
      telemetryAction.put("updateOrgStatus", "org status updated.");
      TelemetryUtil.telemetryProcessingCall(telemetryAction, targetObject, new ArrayList<>());

      return;
    } catch (ProjectCommonException e) {
      sender().tell(e, self());
      return;
    }
  }

  /** Update the Organisation data */
  @SuppressWarnings("unchecked")
  private void updateOrgData(Request actorMessage) {

    Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    try {
      actorMessage.toLower();
      Map<String, Object> request = actorMessage.getRequest();

      String orgId = (String) request.get(JsonKey.ORGANISATION_ID);
      Response result =
          cassandraOperation.getRecordById(
              orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), orgId);
      List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
      Map<String, Object> orgDBO;
      if (!(list.isEmpty())) {
        orgDBO = list.get(0);
      } else {
        ProjectLogger.log(
            "OrganisationManagementActor: updateOrgData invalid orgId", LoggerEnum.DEBUG.name());
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.invalidRequestData.getErrorCode(),
                ResponseCode.invalidRequestData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }
      if (request.containsKey(JsonKey.LOCATION_IDS)) {
        validateLocationIdsForUpdate(
            (List<String>) orgDBO.get(JsonKey.LOCATION_IDS),
            (List<String>) request.get(JsonKey.LOCATION_IDS));
      }
      if (request.containsKey(JsonKey.LOCATION_CODE)
          && !CollectionUtils.isEmpty((List<String>) request.get(JsonKey.LOCATION_CODE))) {
        validateCodeAndAddLocationIds(request);
      }
      if (request.containsKey(JsonKey.ORG_TYPE)
          && !StringUtils.isBlank((String) request.get(JsonKey.ORG_TYPE))) {
        request.put(JsonKey.ORG_TYPE_ID, validateOrgType((String) request.get(JsonKey.ORG_TYPE)));
      }
      if (request.containsKey(JsonKey.LOC_ID)
          && !StringUtils.isBlank((String) request.get(JsonKey.LOC_ID))) {
        request.put(JsonKey.LOC_ID, validateLocationId((String) request.get(JsonKey.LOC_ID)));
      }
      if (!(validateOrgRequest(request))) {
        ProjectLogger.log("REQUESTED DATA IS NOT VALID");
        return;
      }
      if (request.containsKey(JsonKey.EMAIL)
          && !EmailValidator.isEmailValid((String) request.get(JsonKey.EMAIL))) {
        ProjectCommonException.throwClientErrorException(ResponseCode.emailFormatError);
      }
      validateChannelIdForRootOrg(request);
      //
      boolean channelAdded = false;
      if ((!request.containsKey(JsonKey.CHANNEL)) && request.containsKey(JsonKey.PROVIDER)) {
        // then make provider as channel to fetch root org id.
        request.put(JsonKey.CHANNEL, request.get(JsonKey.PROVIDER));
        channelAdded = true;
      }
      if (request.containsKey(JsonKey.CHANNEL)) {
        if (!request.containsKey(JsonKey.IS_ROOT_ORG)
            || !(Boolean) request.get(JsonKey.IS_ROOT_ORG)) {
          String rootOrgId = getRootOrgIdFromChannel((String) request.get(JsonKey.CHANNEL));
          if (!StringUtils.isBlank(rootOrgId) || channelAdded) {
            request.put(
                JsonKey.ROOT_ORG_ID,
                "".equals(rootOrgId) ? JsonKey.DEFAULT_ROOT_ORG_ID : rootOrgId);
          } else {
            ProjectLogger.log("Invalid channel id.");
            ProjectCommonException exception =
                new ProjectCommonException(
                    ResponseCode.invalidChannel.getErrorCode(),
                    ResponseCode.invalidChannel.getErrorMessage(),
                    ResponseCode.CLIENT_ERROR.getResponseCode());
            sender().tell(exception, self());
            return;
          }
        } else if (!channelAdded
            && !validateChannelUniqueness(
                (String) request.get(JsonKey.CHANNEL),
                (String) request.get(JsonKey.ORGANISATION_ID))) {
          ProjectLogger.log("Channel validation failed");
          ProjectCommonException exception =
              new ProjectCommonException(
                  ResponseCode.channelUniquenessInvalid.getErrorCode(),
                  ResponseCode.channelUniquenessInvalid.getErrorMessage(),
                  ResponseCode.CLIENT_ERROR.getResponseCode());
          sender().tell(exception, self());
          return;
        }
      }
      // if channel is not coming and we added it from provider to collect the
      // rootOrgId then
      // remove channel
      if (channelAdded) {
        request.remove(JsonKey.CHANNEL);
      }
      Map<String, Object> addressReq = null;
      if (null != request.get(JsonKey.ADDRESS)) {
        addressReq = (Map<String, Object>) request.get(JsonKey.ADDRESS);
        request.remove(JsonKey.ADDRESS);
      }
      String relation = (String) request.get(JsonKey.RELATION);
      // removing default from request, not allowing user to create default org.
      request.remove(JsonKey.IS_DEFAULT);
      request.remove(JsonKey.RELATION);
      // allow lower case values for source and externalId to the database
      if (request.get(JsonKey.PROVIDER) != null) {
        request.put(JsonKey.PROVIDER, ((String) request.get(JsonKey.PROVIDER)).toLowerCase());
      }
      String externalId = (String) request.get(JsonKey.EXTERNAL_ID);
      if (externalId != null) {
        if (!validateExternalIdUniqueness(
            externalId.toLowerCase(), (String) request.get(JsonKey.ORGANISATION_ID))) {
          ProjectCommonException.throwClientErrorException(
              ResponseCode.errorDuplicateEntry,
              MessageFormat.format(
                  ResponseCode.errorDuplicateEntry.getErrorMessage(),
                  externalId,
                  JsonKey.EXTERNAL_ID));
        }
        request.put(JsonKey.EXTERNAL_ID, externalId);
      }
      String parentOrg = (String) request.get(JsonKey.PARENT_ORG_ID);
      Boolean isValidParent = false;
      ProjectLogger.log("Parent Org Id:" + parentOrg);
      if (!StringUtils.isBlank(parentOrg) && isValidParent(parentOrg)) {
        validateCyclicRelationForOrganisation(request);
        validateRootOrg(request);
        isValidParent = true;
      }

      Map<String, Object> updateOrgDBO = new HashMap<>();
      updateOrgDBO.putAll(request);
      updateOrgDBO.remove(JsonKey.ORGANISATION_ID);
      updateOrgDBO.remove(JsonKey.IS_APPROVED);
      updateOrgDBO.remove(JsonKey.APPROVED_BY);
      updateOrgDBO.remove(JsonKey.APPROVED_DATE);
      updateOrgDBO.remove(JsonKey.STATUS);
      String updatedBy = (String) actorMessage.getRequest().get(JsonKey.REQUESTED_BY);

      boolean isAddressUpdated = false;
      // update address if present in request
      if (null != addressReq && addressReq.size() == 1) {
        if (orgDBO.get(JsonKey.ADDRESS_ID) != null) {
          String addressId = (String) orgDBO.get(JsonKey.ADDRESS_ID);
          addressReq.put(JsonKey.ID, addressId);
          isAddressUpdated = true;
        }
        // add new address record
        else {
          String addressId = ProjectUtil.getUniqueIdFromTimestamp(actorMessage.getEnv());
          addressReq.put(JsonKey.ID, addressId);
          orgDBO.put(JsonKey.ADDRESS_ID, addressId);
        }
        if (!(StringUtils.isBlank(updatedBy))) {
          addressReq.put(JsonKey.UPDATED_BY, updatedBy);
        }
        upsertAddress(addressReq);
        telemetryGenerationForOrgAddress(addressReq, orgDBO, isAddressUpdated);
      }
      if (!StringUtils.isBlank(((String) request.get(JsonKey.HASHTAGID)))) {
        request.put(
            JsonKey.HASHTAGID,
            validateHashTagId(((String) request.get(JsonKey.HASHTAGID)), JsonKey.UPDATE, orgId));
      }
      if (!(StringUtils.isBlank(updatedBy))) {
        updateOrgDBO.put(JsonKey.UPDATED_BY, updatedBy);
      }
      updateOrgDBO.put(JsonKey.UPDATED_DATE, ProjectUtil.getFormattedDate());
      updateOrgDBO.put(JsonKey.ID, orgDBO.get(JsonKey.ID));

      if (isValidParent) {
        upsertOrgMap(
            (String) orgDBO.get(JsonKey.ID),
            parentOrg,
            (String) request.get(JsonKey.ROOT_ORG_ID),
            actorMessage.getEnv(),
            relation);
      }

      // if channel is available then make slug for channel.
      // remove the slug key if coming from user input
      updateOrgDBO.remove(JsonKey.SLUG);
      if (updateOrgDBO.containsKey(JsonKey.CHANNEL)) {

        String slug = Slug.makeSlug((String) updateOrgDBO.getOrDefault(JsonKey.CHANNEL, ""), true);
        if ((boolean) orgDBO.get(JsonKey.IS_ROOT_ORG)) {
          String rootOrgId = getRootOrgIdFromSlug(slug);
          if (StringUtils.isBlank(rootOrgId)
              || (!StringUtils.isBlank(rootOrgId)
                  && rootOrgId.equalsIgnoreCase((String) orgDBO.get(JsonKey.ID)))) {
            updateOrgDBO.put(JsonKey.SLUG, slug);
          } else {
            ProjectCommonException exception =
                new ProjectCommonException(
                    ResponseCode.slugIsNotUnique.getErrorCode(),
                    ResponseCode.slugIsNotUnique.getErrorMessage(),
                    ResponseCode.CLIENT_ERROR.getResponseCode());
            sender().tell(exception, self());
            return;
          }
        } else {
          updateOrgDBO.put(JsonKey.SLUG, slug);
        }
      }

      if (null != orgDBO.get(JsonKey.IS_ROOT_ORG) && (boolean) orgDBO.get(JsonKey.IS_ROOT_ORG)) {
        String channel = (String) orgDBO.get(JsonKey.CHANNEL);
        String updateOrgDBOChannel = (String) updateOrgDBO.get(JsonKey.CHANNEL);
        if (null != updateOrgDBOChannel
            && null != channel
            && !(updateOrgDBOChannel.equals(channel))) {
          Map<String, Object> tempMap = new HashMap<>();
          tempMap.put(JsonKey.CHANNEL, updateOrgDBOChannel);
          tempMap.put(JsonKey.HASHTAGID, orgDBO.get(JsonKey.HASHTAGID));
          tempMap.put(JsonKey.DESCRIPTION, orgDBO.get(JsonKey.DESCRIPTION));
          boolean bool = Util.updateChannel(tempMap);
          if (!bool) {
            ProjectCommonException exception =
                new ProjectCommonException(
                    ResponseCode.channelRegFailed.getErrorCode(),
                    ResponseCode.channelRegFailed.getErrorMessage(),
                    ResponseCode.SERVER_ERROR.getResponseCode());
            sender().tell(exception, self());
            return;
          }
        }
      }

      // check contactDetail filed is coming or not. it will always come as list of
      // map
      List<Map<String, Object>> listOfMap = null;
      if (updateOrgDBO.containsKey(JsonKey.CONTACT_DETAILS)) {
        listOfMap = (List<Map<String, Object>>) updateOrgDBO.get(JsonKey.CONTACT_DETAILS);
        if (listOfMap != null && !listOfMap.isEmpty()) {
          try {
            updateOrgDBO.put(JsonKey.CONTACT_DETAILS, mapper.writeValueAsString(listOfMap));
          } catch (IOException e) {
            ProjectLogger.log(e.getMessage(), e);
          }
        }
      }
      // This will remove all extra unnecessary parameter from request
      Organisation org = mapper.convertValue(updateOrgDBO, Organisation.class);
      updateOrgDBO = mapper.convertValue(org, Map.class);
      Response response =
          cassandraOperation.updateRecord(
              orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), updateOrgDBO);
      response.getResult().put(JsonKey.ORGANISATION_ID, orgDBO.get(JsonKey.ID));
      sender().tell(response, self());

      targetObject =
          TelemetryUtil.generateTargetObject(
              (String) orgDBO.get(JsonKey.ID), JsonKey.ORGANISATION, JsonKey.UPDATE, null);
      TelemetryUtil.telemetryProcessingCall(updateOrgDBO, targetObject, correlatedObject);

      if (null != addressReq) {
        updateOrgDBO.put(JsonKey.ADDRESS, addressReq);
      }
      if (listOfMap != null) {
        updateOrgDBO.put(JsonKey.CONTACT_DETAILS, listOfMap);
      } else {
        listOfMap = new ArrayList<>();
        updateOrgDBO.put(JsonKey.CONTACT_DETAILS, listOfMap);
      }
      Request orgRequest = new Request();
      orgRequest.getRequest().put(JsonKey.ORGANISATION, updateOrgDBO);
      orgRequest.setOperation(ActorOperations.UPDATE_ORG_INFO_ELASTIC.getValue());
      tellToAnother(orgRequest);
    } catch (ProjectCommonException e) {
      sender().tell(e, self());
      return;
    }
  }

  private void validateLocationIdsForUpdate(
      List<String> dbLocationIds, List<String> updateLocationIds) {
    if (dbLocationIds == null && updateLocationIds != null)
      ProjectCommonException.throwClientErrorException(
          ResponseCode.unupdatableField,
          MessageFormat.format(
              ResponseCode.unupdatableField.getErrorMessage(), JsonKey.LOCATION_IDS));
    if (dbLocationIds != null && updateLocationIds != null) {
      if (!CollectionUtils.isEqualCollection(dbLocationIds, updateLocationIds)) {
        ProjectCommonException.throwClientErrorException(
            ResponseCode.unupdatableField,
            MessageFormat.format(
                ResponseCode.unupdatableField.getErrorMessage(), JsonKey.LOCATION_IDS));
      }
    }
  }

  private void telemetryGenerationForOrgAddress(
      Map<String, Object> addressReq, Map<String, Object> orgDBO, boolean isAddressUpdated) {

    String addressState = JsonKey.CREATE;
    if (isAddressUpdated) {
      addressState = JsonKey.UPDATE;
    }
    Map<String, Object> targetObject =
        TelemetryUtil.generateTargetObject(
            (String) addressReq.get(JsonKey.ID), JsonKey.ADDRESS, addressState, null);
    List<Map<String, Object>> correlatedObject = new ArrayList<>();
    TelemetryUtil.generateCorrelatedObject(
        (String) orgDBO.get(JsonKey.ID), JsonKey.ORGANISATION, null, correlatedObject);
    TelemetryUtil.telemetryProcessingCall(addressReq, targetObject, correlatedObject);
  }

  /** Method to add member to the organisation */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void addMemberOrganisation(Request actorMessage) {
    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    Response response = null;
    actorMessage.toLower();
    Util.DbInfo userOrgDbInfo = Util.dbInfoMap.get(JsonKey.USER_ORG_DB);
    Util.DbInfo organisationDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
    Map<String, Object> usrOrgData = actorMessage.getRequest();
    if (!(validateOrgRequestForMembers(usrOrgData))) {
      ProjectLogger.log("REQUESTED DATA IS NOT VALID");
      return;
    }
    if (!(validateUsrRequest(usrOrgData))) {
      ProjectLogger.log("REQUESTED DATA IS NOT VALID");
      return;
    }
    // remove source and external id
    usrOrgData.remove(JsonKey.EXTERNAL_ID);
    usrOrgData.remove(JsonKey.SOURCE);
    usrOrgData.remove(JsonKey.PROVIDER);
    usrOrgData.remove(JsonKey.USERNAME);
    usrOrgData.remove(JsonKey.USER_NAME);
    usrOrgData.put(JsonKey.IS_DELETED, false);

    String updatedBy = null;
    String orgId = null;
    String userId = null;
    List<String> roles = new ArrayList<>();
    orgId = (String) usrOrgData.get(JsonKey.ORGANISATION_ID);
    userId = (String) usrOrgData.get(JsonKey.USER_ID);
    if (isNotNull(usrOrgData.get(JsonKey.REQUESTED_BY))) {
      updatedBy = (String) usrOrgData.get(JsonKey.REQUESTED_BY);
      usrOrgData.remove(JsonKey.REQUESTED_BY);
    }
    if (isNotNull(usrOrgData.get(JsonKey.ROLES))) {
      roles.addAll((List<String>) usrOrgData.get(JsonKey.ROLES));
      if (!((List<String>) usrOrgData.get(JsonKey.ROLES)).isEmpty()) {
        String msg = Util.validateRoles((List<String>) usrOrgData.get(JsonKey.ROLES));
        if (!msg.equalsIgnoreCase(JsonKey.SUCCESS)) {
          throw new ProjectCommonException(
              ResponseCode.invalidRole.getErrorCode(),
              ResponseCode.invalidRole.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
        }
      }
    }

    usrOrgData.remove(JsonKey.ROLE);
    if (isNull(roles) && roles.isEmpty()) {
      // create exception here invalid request data and tell the exception , then
      // return
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.invalidRequestData.getErrorCode(),
              ResponseCode.invalidRequestData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return;
    }
    if (!roles.isEmpty()) usrOrgData.put(JsonKey.ROLES, roles);
    // check user already exist for the org or not
    Map<String, Object> requestData = new HashMap<>();
    requestData.put(JsonKey.USER_ID, userId);
    requestData.put(JsonKey.ORGANISATION_ID, orgId);
    Response result =
        cassandraOperation.getRecordsByProperties(
            userOrgDbInfo.getKeySpace(), userOrgDbInfo.getTableName(), requestData);

    List list = (List) result.get(JsonKey.RESPONSE);
    Map<String, Object> tempOrgap = null;
    boolean isNewRecord = false;
    if (!list.isEmpty()) {
      tempOrgap = (Map<String, Object>) list.get(0);
      if (null != tempOrgap && !((boolean) tempOrgap.get(JsonKey.IS_DELETED))) {
        // user already enrolled for the organisation
        response = new Response();
        response.getResult().put(JsonKey.RESPONSE, ResponseMessage.Message.EXISTING_ORG_MEMBER);
        sender().tell(response, self());
        return;
      } else if (null != tempOrgap && ((boolean) tempOrgap.get(JsonKey.IS_DELETED))) {
        usrOrgData.put(JsonKey.ID, tempOrgap.get(JsonKey.ID));
      }
    } else {
      usrOrgData.put(JsonKey.ID, ProjectUtil.getUniqueIdFromTimestamp(actorMessage.getEnv()));
      isNewRecord = true;
    }
    if (!(StringUtils.isBlank(updatedBy))) {
      String updatedByName = Util.getUserNamebyUserId(updatedBy);
      usrOrgData.put(JsonKey.ADDED_BY, updatedBy);
      usrOrgData.put(JsonKey.APPROVED_BY, updatedBy);
      if (!StringUtils.isBlank(updatedByName)) {
        usrOrgData.put(JsonKey.ADDED_BY_NAME, updatedByName);
      }
    }
    usrOrgData.put(JsonKey.ORG_JOIN_DATE, ProjectUtil.getFormattedDate());
    usrOrgData.put(JsonKey.APPROOVE_DATE, ProjectUtil.getFormattedDate());
    usrOrgData.put(JsonKey.IS_REJECTED, false);
    usrOrgData.put(JsonKey.IS_APPROVED, true);
    usrOrgData.put(JsonKey.IS_DELETED, false);
    if (isNewRecord) {
      response =
          cassandraOperation.insertRecord(
              userOrgDbInfo.getKeySpace(), userOrgDbInfo.getTableName(), usrOrgData);
    } else {
      response =
          cassandraOperation.updateRecord(
              userOrgDbInfo.getKeySpace(), userOrgDbInfo.getTableName(), usrOrgData);
    }
    Response orgResult =
        cassandraOperation.getRecordById(
            organisationDbInfo.getKeySpace(), organisationDbInfo.getTableName(), orgId);

    List orgList = (List) orgResult.get(JsonKey.RESPONSE);
    Map<String, Object> newOrgMap = new HashMap<>();
    if (!orgList.isEmpty()) {
      Integer count = 0;
      Map<String, Object> orgMap = (Map<String, Object>) orgList.get(0);
      if (isNotNull(orgMap.get(JsonKey.NO_OF_MEMBERS))) {
        count = (Integer) orgMap.get(JsonKey.NO_OF_MEMBERS);
      }
      newOrgMap.put(JsonKey.ID, orgId);
      newOrgMap.put(JsonKey.NO_OF_MEMBERS, count + 1);
      cassandraOperation.updateRecord(
          organisationDbInfo.getKeySpace(), organisationDbInfo.getTableName(), newOrgMap);
    }

    sender().tell(response, self());

    // update ES with latest data through background job manager
    if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
      ProjectLogger.log("method call going to satrt for ES--.....");
      Request request = new Request();
      request.setOperation(ActorOperations.UPDATE_USER_ORG_ES.getValue());
      request.getRequest().put(JsonKey.USER, usrOrgData);
      ProjectLogger.log("making a call to save user data to ES");
      try {
        tellToAnother(request);
      } catch (Exception ex) {
        ProjectLogger.log(
            "Exception Occurred during saving user to Es while addMemberOrganisation : ", ex);
      }
    } else {
      ProjectLogger.log("no call for ES to save user");
    }

    targetObject = TelemetryUtil.generateTargetObject(userId, JsonKey.USER, JsonKey.CREATE, null);
    TelemetryUtil.generateCorrelatedObject(userId, JsonKey.USER, null, correlatedObject);
    TelemetryUtil.generateCorrelatedObject(orgId, JsonKey.ORGANISATION, null, correlatedObject);
    Map<String, Object> telemetryAction = new HashMap<>();
    telemetryAction.put("orgMembershipAdded", "orgMembershipAdded");
    TelemetryUtil.telemetryProcessingCall(telemetryAction, targetObject, correlatedObject);
  }

  /** Method to remove member from the organisation */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void removeMemberOrganisation(Request actorMessage) {
    Response response = null;
    // object of telemetry event...
    List<Map<String, Object>> correlatedObject = new ArrayList<>();
    actorMessage.toLower();
    Util.DbInfo userOrgDbInfo = Util.dbInfoMap.get(JsonKey.USER_ORG_DB);
    Util.DbInfo organisationDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
    Map<String, Object> usrOrgData = actorMessage.getRequest();
    if (!(validateUsrRequest(usrOrgData))) {
      ProjectLogger.log("REQUESTED DATA IS NOT VALID");
      return;
    }
    if (!(validateOrgRequestForMembers(usrOrgData))) {
      ProjectLogger.log("REQUESTED DATA IS NOT VALID");
      return;
    }

    // remove source and external id
    usrOrgData.remove(JsonKey.EXTERNAL_ID);
    usrOrgData.remove(JsonKey.SOURCE);
    usrOrgData.remove(JsonKey.USERNAME);
    usrOrgData.remove(JsonKey.USER_NAME);

    String updatedBy = null;
    String orgId = null;
    String userId = null;

    orgId = (String) usrOrgData.get(JsonKey.ORGANISATION_ID);
    userId = (String) usrOrgData.get(JsonKey.USER_ID);

    if (isNotNull(usrOrgData.get(JsonKey.REQUESTED_BY))) {
      updatedBy = (String) usrOrgData.get(JsonKey.REQUESTED_BY);
      usrOrgData.remove(JsonKey.REQUESTED_BY);
    }
    // check user already exist for the org or not
    Map<String, Object> requestData = new HashMap<>();
    requestData.put(JsonKey.USER_ID, userId);
    requestData.put(JsonKey.ORGANISATION_ID, orgId);

    Response result =
        cassandraOperation.getRecordsByProperties(
            userOrgDbInfo.getKeySpace(), userOrgDbInfo.getTableName(), requestData);

    List list = (List) result.get(JsonKey.RESPONSE);
    if (list.isEmpty()) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.invalidRequestData.getErrorCode(),
              ResponseCode.invalidRequestData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return;
    } else {
      Map<String, Object> dataMap = (Map<String, Object>) list.get(0);
      if (null != dataMap.get(JsonKey.IS_DELETED) && (boolean) dataMap.get(JsonKey.IS_DELETED)) {
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.userInactiveForThisOrg.getErrorCode(),
                ResponseCode.userInactiveForThisOrg.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }
      if (!(StringUtils.isBlank(updatedBy))) {
        dataMap.put(JsonKey.UPDATED_BY, updatedBy);
      }
      dataMap.put(JsonKey.ORG_LEFT_DATE, ProjectUtil.getFormattedDate());
      dataMap.put(JsonKey.UPDATED_DATE, ProjectUtil.getFormattedDate());
      dataMap.put(JsonKey.IS_DELETED, true);
      response =
          cassandraOperation.updateRecord(
              userOrgDbInfo.getKeySpace(), userOrgDbInfo.getTableName(), dataMap);
      Map<String, Object> newOrgMap = new HashMap<>();

      Response orgresult =
          cassandraOperation.getRecordById(
              organisationDbInfo.getKeySpace(), organisationDbInfo.getTableName(), orgId);
      List orgList = (List) orgresult.get(JsonKey.RESPONSE);
      if (!orgList.isEmpty()) {
        Map<String, Object> orgMap = (Map<String, Object>) orgList.get(0);
        if (isNotNull(orgMap.get(JsonKey.NO_OF_MEMBERS))) {
          Integer count = (Integer) orgMap.get(JsonKey.NO_OF_MEMBERS);
          newOrgMap.put(JsonKey.ID, orgId);
          newOrgMap.put(JsonKey.NO_OF_MEMBERS, count == 0 ? 0 : (count - 1));
          cassandraOperation.updateRecord(
              organisationDbInfo.getKeySpace(), organisationDbInfo.getTableName(), newOrgMap);
        }
      }
      sender().tell(response, self());

      // update ES with latest data through background job manager
      if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
        ProjectLogger.log("method call going to satrt for ES--.....");
        Request request = new Request();
        request.setOperation(ActorOperations.REMOVE_USER_ORG_ES.getValue());
        request.getRequest().put(JsonKey.USER, dataMap);
        ProjectLogger.log("making a call to save user data to ES");
        try {
          tellToAnother(request);
        } catch (Exception ex) {
          ProjectLogger.log(
              "Exception Occurred during saving user to Es while removing memeber from Organisation : ",
              ex);
        }
      } else {
        ProjectLogger.log("no call for ES to save user");
      }
      Map<String, Object> targetObject =
          TelemetryUtil.generateTargetObject(userId, JsonKey.USER, JsonKey.CREATE, null);
      TelemetryUtil.generateCorrelatedObject(userId, JsonKey.USER, null, correlatedObject);
      TelemetryUtil.generateCorrelatedObject(orgId, JsonKey.ORGANISATION, null, correlatedObject);
      Map<String, Object> telemetryAction = new HashMap<>();
      telemetryAction.put("orgMembershipRemoved", "orgMembershipRemoved");
      TelemetryUtil.telemetryProcessingCall(telemetryAction, targetObject, correlatedObject);
    }
  }

  /** Provides the details of the Organisation */
  private void getOrgDetails(Request actorMessage) {
    actorMessage.toLower();
    Map<String, Object> request = actorMessage.getRequest();
    if (!(validateOrgRequest(request))) {
      ProjectLogger.log("REQUESTED DATA IS NOT VALID");
      return;
    }
    String orgId = (String) request.get(JsonKey.ORGANISATION_ID);
    Map<String, Object> result =
        ElasticSearchUtil.getDataByIdentifier(
            ProjectUtil.EsIndex.sunbird.getIndexName(),
            ProjectUtil.EsType.organisation.getTypeName(),
            orgId);

    if (MapUtils.isEmpty(result)) {
      throw new ProjectCommonException(
          ResponseCode.orgDoesNotExist.getErrorCode(),
          ResponseCode.orgDoesNotExist.getErrorMessage(),
          ResponseCode.RESOURCE_NOT_FOUND.getResponseCode());
    }

    Response response = new Response();
    response.put(JsonKey.RESPONSE, result);
    sender().tell(response, self());
  }

  /** Inserts an address if not present, else updates the existing address */
  private void upsertAddress(Map<String, Object> addressReq) {

    Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ADDRESS_DB);
    cassandraOperation.upsertRecord(orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), addressReq);
  }

  /**
   * Inserts the Organisation to Organisation relation is not exists, else Updates the Organisation
   * to Organisation relation
   */
  public void upsertOrgMap(
      String orgId, String parentOrgId, String rootOrgId, int env, String relation) {
    Util.DbInfo orgMapDbInfo = Util.dbInfoMap.get(JsonKey.ORG_MAP_DB);
    String orgMapId = ProjectUtil.getUniqueIdFromTimestamp(env);
    Map<String, Object> orgMap = new HashMap<>();
    orgMap.put(JsonKey.ID, orgMapId);
    orgMap.put(JsonKey.ORG_ID_ONE, parentOrgId);
    orgMap.put(JsonKey.RELATION, relation);
    orgMap.put(JsonKey.ORG_ID_TWO, orgId);
    orgMap.put(JsonKey.ROOT_ORG_ID, rootOrgId);
    cassandraOperation.insertRecord(
        orgMapDbInfo.getKeySpace(), orgMapDbInfo.getTableName(), orgMap);
  }

  /** Checks whether the parent Organisation exists */
  @SuppressWarnings("unchecked")
  public Boolean isValidParent(String parentId) {
    Util.DbInfo userdbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
    Response result =
        cassandraOperation.getRecordById(
            userdbInfo.getKeySpace(), userdbInfo.getTableName(), parentId);
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
    if (!(list.isEmpty())) {
      return true;
    }
    throw new ProjectCommonException(
        ResponseCode.invalidParentId.getErrorCode(),
        ResponseCode.invalidParentId.getErrorMessage(),
        ResponseCode.CLIENT_ERROR.getResponseCode());
  }

  /** Checks whether parentId has a parent relation with the childId */
  @SuppressWarnings("unchecked")
  public Boolean isChildOf(String parentId, String childId) {
    Util.DbInfo userdbInfo = Util.dbInfoMap.get(JsonKey.ORG_MAP_DB);
    Map<String, Object> properties = new HashMap<>();
    properties.put(JsonKey.ORG_ID_ONE, parentId);
    properties.put(JsonKey.ORG_ID_TWO, childId);
    Response result =
        cassandraOperation.getRecordsByProperties(
            userdbInfo.getKeySpace(), userdbInfo.getTableName(), properties);
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
    return (!(list.isEmpty()));
  }

  /**
   * validates if the Organisation and parent Organisation has the same root Organisation else
   * throws error
   */
  @SuppressWarnings("unchecked")
  public void validateRootOrg(Map<String, Object> request) {
    ProjectLogger.log("Validating Root org started---");
    Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
    if (!StringUtils.isBlank((String) request.get(JsonKey.PARENT_ORG_ID))) {
      Response result =
          cassandraOperation.getRecordById(
              orgDbInfo.getKeySpace(),
              orgDbInfo.getTableName(),
              (String) request.get(JsonKey.PARENT_ORG_ID));
      List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
      Map<String, Object> parentOrgDBO = new HashMap<>();
      if (!(list.isEmpty())) {
        parentOrgDBO = list.get(0);
      }
      if (!StringUtils.isBlank((String) parentOrgDBO.get(JsonKey.ROOT_ORG_ID))) {
        String parentRootOrg = (String) parentOrgDBO.get(JsonKey.ROOT_ORG_ID);
        if (null != request.get(JsonKey.ROOT_ORG_ID)
            && !parentRootOrg.equalsIgnoreCase(request.get(JsonKey.ROOT_ORG).toString())) {
          throw new ProjectCommonException(
              ResponseCode.invalidRootOrganisationId.getErrorCode(),
              ResponseCode.invalidRootOrganisationId.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
        } else {
          // set the parent root org to this organisation.
          request.put(JsonKey.ROOT_ORG_ID, parentRootOrg);
        }
      }
    }
    ProjectLogger.log("Validating Root org ended successfully---");
  }

  /** validates whether the channelId is present for the parent Organisation */
  public boolean validateChannelIdForRootOrg(Map<String, Object> request) {
    ProjectLogger.log("Doing relation check");
    if (null != request.get(JsonKey.IS_ROOT_ORG) && (Boolean) request.get(JsonKey.IS_ROOT_ORG)) {
      if (StringUtils.isBlank((String) request.get(JsonKey.CHANNEL))) {
        throw new ProjectCommonException(
            ResponseCode.channelIdRequiredForRootOrg.getErrorCode(),
            ResponseCode.channelIdRequiredForRootOrg.getErrorMessage(),
            ResponseCode.CLIENT_ERROR.getResponseCode());
      } else if (null != request.get(JsonKey.CHANNEL)
          || !StringUtils.isBlank((String) request.get(JsonKey.CHANNEL))) {
        return true;
      }
    }
    ProjectLogger.log("Relation check end successfully!");
    return false;
  }

  /** validates if the relation is cyclic */
  public void validateCyclicRelationForOrganisation(Map<String, Object> request) {
    if (!StringUtils.isBlank((String) request.get(JsonKey.PARENT_ORG_ID))
        && isChildOf(
            (String) request.get(JsonKey.ORGANISATION_ID),
            (String) request.get(JsonKey.PARENT_ORG_ID))) {
      throw new ProjectCommonException(
          ResponseCode.cyclicValidationError.getErrorCode(),
          ResponseCode.cyclicValidationError.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
  }

  /**
   * Validates whether the organisation or source with externalId exists in DB
   *
   * @param req Request from the user
   * @return boolean
   */
  @SuppressWarnings("unchecked")
  private boolean validateOrgRequest(Map<String, Object> req) {

    if (isNull(req)) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.invalidRequestData.getErrorCode(),
              ResponseCode.invalidRequestData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return false;
    }

    if (isNull(req.get(JsonKey.ORGANISATION_ID))) {

      if (isNull(req.get(JsonKey.PROVIDER)) || isNull(req.get(JsonKey.EXTERNAL_ID))) {
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.invalidRequestData.getErrorCode(),
                ResponseCode.invalidRequestData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return false;
      }

      // fetch orgid from database on basis of source and external id and put orgid
      // into request .
      Util.DbInfo userdbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);

      Map<String, Object> requestDbMap = new HashMap<>();
      requestDbMap.put(JsonKey.PROVIDER, req.get(JsonKey.PROVIDER));
      requestDbMap.put(JsonKey.EXTERNAL_ID, req.get(JsonKey.EXTERNAL_ID));

      Response result =
          cassandraOperation.getRecordsByProperties(
              userdbInfo.getKeySpace(), userdbInfo.getTableName(), requestDbMap);
      List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);

      if (list.isEmpty()) {
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.invalidRequestData.getErrorCode(),
                ResponseCode.invalidRequestData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return false;
      }

      req.put(JsonKey.ORGANISATION_ID, list.get(0).get(JsonKey.ID));
    }
    return true;
  }

  /**
   * Validates whether the organisation or source with externalId exists in DB
   *
   * @param req
   * @return boolean
   */
  @SuppressWarnings("unchecked")
  private boolean validateOrgRequestForMembers(Map<String, Object> req) {
    if (isNull(req)) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.invalidRequestData.getErrorCode(),
              ResponseCode.invalidRequestData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return false;
    }
    if (isNull(req.get(JsonKey.ORGANISATION_ID))
        && (isNull(req.get(JsonKey.PROVIDER)) || isNull(req.get(JsonKey.EXTERNAL_ID)))) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.sourceAndExternalIdValidationError.getErrorCode(),
              ResponseCode.sourceAndExternalIdValidationError.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return false;
    }
    // fetch orgid from database on basis of source and external id and put orgid
    // into request .
    Util.DbInfo orgDBInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);

    Map<String, Object> requestDbMap = new HashMap<>();
    if (!StringUtils.isBlank((String) req.get(JsonKey.ORGANISATION_ID))) {
      requestDbMap.put(JsonKey.ID, req.get(JsonKey.ORGANISATION_ID));
    } else {
      requestDbMap.put(JsonKey.PROVIDER, req.get(JsonKey.PROVIDER));
      requestDbMap.put(JsonKey.EXTERNAL_ID, req.get(JsonKey.EXTERNAL_ID));
    }

    Response result =
        cassandraOperation.getRecordsByProperties(
            orgDBInfo.getKeySpace(), orgDBInfo.getTableName(), requestDbMap);
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);

    if (list.isEmpty()) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.invalidOrgData.getErrorCode(),
              ResponseCode.invalidOrgData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return false;
    }
    req.put(JsonKey.ORGANISATION_ID, list.get(0).get(JsonKey.ID));
    req.put(JsonKey.HASHTAGID, list.get(0).get(JsonKey.HASHTAGID));
    return true;
  }

  /**
   * Validates where the userId or provider with userName is in database and is valid
   *
   * @param req
   * @return boolean
   */
  @SuppressWarnings("unchecked")
  private boolean validateUsrRequest(Map<String, Object> req) {
    if (isNull(req)) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.invalidRequestData.getErrorCode(),
              ResponseCode.invalidRequestData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return false;
    }
    Map<String, Object> data = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    data.putAll(req);
    if (isNull(data.get(JsonKey.USER_ID)) && isNull(data.get(JsonKey.USERNAME))) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.usrValidationError.getErrorCode(),
              ResponseCode.usrValidationError.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return false;
    }
    Response result = null;
    Util.DbInfo usrDbInfo = Util.dbInfoMap.get(JsonKey.USER_DB);
    Map<String, Object> requestDbMap = new HashMap<>();
    if (!StringUtils.isBlank((String) data.get(JsonKey.USER_ID))) {
      requestDbMap.put(JsonKey.ID, data.get(JsonKey.USER_ID));
      result =
          cassandraOperation.getRecordsByProperty(
              usrDbInfo.getKeySpace(),
              usrDbInfo.getTableName(),
              JsonKey.ID,
              data.get(JsonKey.USER_ID));
    } else {
      requestDbMap.put(JsonKey.PROVIDER, data.get(JsonKey.PROVIDER));
      requestDbMap.put(JsonKey.USERNAME, data.get(JsonKey.USERNAME));
      if (data.containsKey(JsonKey.PROVIDER)
          && !StringUtils.isBlank((String) data.get(JsonKey.PROVIDER))) {
        data.put(
            JsonKey.LOGIN_ID,
            (String) data.get(JsonKey.USERNAME) + "@" + (String) data.get(JsonKey.PROVIDER));
      } else {
        data.put(JsonKey.LOGIN_ID, data.get(JsonKey.USERNAME));
      }

      String loginId = "";
      try {
        loginId = encryptionService.encryptData((String) data.get(JsonKey.LOGIN_ID));
      } catch (Exception e) {
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.userDataEncryptionError.getErrorCode(),
                ResponseCode.userDataEncryptionError.getErrorMessage(),
                ResponseCode.SERVER_ERROR.getResponseCode());
        sender().tell(exception, self());
      }
      result =
          cassandraOperation.getRecordsByProperty(
              usrDbInfo.getKeySpace(), usrDbInfo.getTableName(), JsonKey.LOGIN_ID, loginId);
    }
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
    if (list.isEmpty()) {
      ProjectCommonException exception =
          new ProjectCommonException(
              ResponseCode.invalidUsrData.getErrorCode(),
              ResponseCode.invalidUsrData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
      return false;
    }
    req.put(JsonKey.USER_ID, list.get(0).get(JsonKey.ID));

    return true;
  }

  private List<Map<String, Object>> getOrg(String channel) {
    ProjectLogger.log(
        "OrganisationManagementActor:getOrg: channel = " + channel, LoggerEnum.INFO.name());
    Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
    Map<String, Object> requestData = new HashMap<>();
    requestData.put(JsonKey.CHANNEL, channel);
    requestData.put(JsonKey.IS_ROOT_ORG, true);
    Response result =
        cassandraOperation.getRecordsByProperties(
            orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), requestData);
    ProjectLogger.log(
        "OrganisationManagementActor:getOrg: result = " + result.toString(),
        LoggerEnum.INFO.name());
    ProjectLogger.log(
        "OrganisationManagementActor:getOrg: result.response = "
            + result.get(JsonKey.RESPONSE).toString(),
        LoggerEnum.INFO.name());
    return (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
  }

  private String getRootOrgIdFromChannel(String channel) {
    ProjectLogger.log(
        "OrganisationManagementActor:getRootOrgIdFromChannel: channel = " + channel,
        LoggerEnum.INFO.name());
    if (!StringUtils.isBlank(channel)) {
      List<Map<String, Object>> list = getOrg(channel);
      if (!list.isEmpty()) return (String) list.get(0).getOrDefault(JsonKey.ID, "");
    }

    return "";
  }

  private String getRootOrgIdFromSlug(String slug) {
    if (!StringUtils.isBlank(slug)) {
      Map<String, Object> filters = new HashMap<>();
      filters.put(JsonKey.SLUG, slug);
      filters.put(JsonKey.IS_ROOT_ORG, true);
      Map<String, Object> esResult =
          elasticSearchComplexSearch(
              filters, EsIndex.sunbird.getIndexName(), EsType.organisation.getTypeName());
      if (isNotNull(esResult)
          && esResult.containsKey(JsonKey.CONTENT)
          && isNotNull(esResult.get(JsonKey.CONTENT))
          && (!((List) esResult.get(JsonKey.CONTENT)).isEmpty())) {
        Map<String, Object> esContent =
            ((List<Map<String, Object>>) esResult.get(JsonKey.CONTENT)).get(0);
        return (String) esContent.getOrDefault(JsonKey.ID, "");
      }
    }
    return "";
  }

  private boolean isSlugUnique(String slug) {
    if (!StringUtils.isBlank(slug)) {
      Map<String, Object> filters = new HashMap<>();
      filters.put(JsonKey.SLUG, slug);
      filters.put(JsonKey.IS_ROOT_ORG, true);
      Map<String, Object> esResult =
          elasticSearchComplexSearch(
              filters, EsIndex.sunbird.getIndexName(), EsType.organisation.getTypeName());
      if (isNotNull(esResult)
          && esResult.containsKey(JsonKey.CONTENT)
          && isNotNull(esResult.get(JsonKey.CONTENT))) {
        return (((List) esResult.get(JsonKey.CONTENT)).isEmpty());
      }
    }
    return false;
  }

  private Map<String, Object> elasticSearchComplexSearch(
      Map<String, Object> filters, String index, String type) {

    SearchDTO searchDTO = new SearchDTO();
    searchDTO.getAdditionalProperties().put(JsonKey.FILTERS, filters);

    return ElasticSearchUtil.complexSearch(searchDTO, index, type);
  }

  /**
   * validates if channel is already present in the organisation while Updating
   *
   * @param channel
   * @return boolean
   */
  @SuppressWarnings("unchecked")
  private boolean validateChannelUniqueness(String channel, String orgId) {
    if (!StringUtils.isBlank(channel)) {
      return validateFieldUniqueness(JsonKey.CHANNEL, channel, orgId);
    }
    return (orgId == null);
  }

  private boolean validateExternalIdUniqueness(String externalId, String orgId) {
    return validateFieldUniqueness(JsonKey.EXTERNAL_ID, externalId, orgId);
  }

  private boolean validateFieldUniqueness(String key, String value, String orgId) {
    if (value != null) {
      Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
      Response result =
          cassandraOperation.getRecordsByProperty(
              orgDbInfo.getKeySpace(), orgDbInfo.getTableName(), key, value);
      List<Map<String, Object>> list = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
      if ((list.isEmpty())) {
        return true;
      } else {
        if (orgId == null) {
          return false;
        }
        Map<String, Object> data = list.get(0);
        String id = (String) data.get(JsonKey.ID);
        if (id.equalsIgnoreCase(orgId)) {
          return true;
        } else {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * This method will do the channel uniqueness validation
   *
   * @param req
   */
  private void validateChannel(Map<String, Object> req) {
    if (!req.containsKey(JsonKey.IS_ROOT_ORG) || !(Boolean) req.get(JsonKey.IS_ROOT_ORG)) {
      String rootOrgId = getRootOrgIdFromChannel((String) req.get(JsonKey.CHANNEL));
      if (!StringUtils.isBlank(rootOrgId)) {
        req.put(JsonKey.ROOT_ORG_ID, rootOrgId);
      } else {
        ProjectLogger.log("Invalid channel id.");
        throw new ProjectCommonException(
            ResponseCode.invalidChannel.getErrorCode(),
            ResponseCode.invalidChannel.getErrorMessage(),
            ResponseCode.CLIENT_ERROR.getResponseCode());
      }
    } else if (!validateChannelUniqueness((String) req.get(JsonKey.CHANNEL), null)) {
      ProjectLogger.log("Channel validation failed");
      throw new ProjectCommonException(
          ResponseCode.channelUniquenessInvalid.getErrorCode(),
          ResponseCode.channelUniquenessInvalid.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
  }
}
