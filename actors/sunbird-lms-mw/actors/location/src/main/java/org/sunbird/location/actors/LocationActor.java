package org.sunbird.location.actors;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LocationActorOperation;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.dto.SearchDTO;
import org.sunbird.learner.util.Util;
import org.sunbird.location.dao.LocationDao;
import org.sunbird.location.dao.impl.LocationDaoFactory;
import org.sunbird.location.util.LocationRequestValidator;
import org.sunbird.models.location.Location;
import org.sunbird.models.location.apirequest.UpsertLocationRequest;

/**
 * This class will handle all location related request.
 *
 * @author Amit Kumar
 */
@ActorConfig(
  tasks = {"createLocation", "updateLocation", "searchLocation", "deleteLocation"},
  asyncTasks = {}
)
public class LocationActor extends BaseLocationActor {

  private ObjectMapper mapper = new ObjectMapper();
  private LocationDao locationDao = LocationDaoFactory.getInstance();

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, JsonKey.LOCATION);
    ExecutionContext.setRequestId(request.getRequestId());
    String operation = request.getOperation();
    switch (operation) {
      case "createLocation":
        createLocation(ProjectUtil.convertToRequestPojo(request, UpsertLocationRequest.class));
        break;
      case "updateLocation":
        updateLocation(ProjectUtil.convertToRequestPojo(request, UpsertLocationRequest.class));
        break;
      case "searchLocation":
        searchLocation(request);
        break;
      case "deleteLocation":
        deleteLocation(request);
        break;
      default:
        onReceiveUnsupportedOperation("LocationActor");
    }
  }

  private void createLocation(UpsertLocationRequest locationRequest) {
    try {
      validateUpsertLocnReq(locationRequest, JsonKey.CREATE);
      // put unique identifier in request for Id
      String id = ProjectUtil.generateUniqueId();
      locationRequest.setId(id);
      Location location = mapper.convertValue(locationRequest, Location.class);
      Response response = locationDao.create(location);
      sender().tell(response, self());
      ProjectLogger.log("Insert location data to ES");
      saveDataToES(mapper.convertValue(location, Map.class), JsonKey.INSERT);
      generateTelemetryForLocation(id, mapper.convertValue(location, Map.class), JsonKey.CREATE);
    } catch (Exception ex) {
      ProjectLogger.log(ex.getMessage(), ex);
      sender().tell(ex, self());
    }
  }

  private void updateLocation(UpsertLocationRequest locationRequest) {
    try {
      validateUpsertLocnReq(locationRequest, JsonKey.UPDATE);
      Location location = mapper.convertValue(locationRequest, Location.class);
      Response response = locationDao.update(location);
      sender().tell(response, self());
      ProjectLogger.log("Update location data to ES");
      saveDataToES(mapper.convertValue(location, Map.class), JsonKey.UPDATE);
      generateTelemetryForLocation(
          location.getId(), mapper.convertValue(location, Map.class), JsonKey.UPDATE);
    } catch (Exception ex) {
      ProjectLogger.log(ex.getMessage(), ex);
      sender().tell(ex, self());
    }
  }

  private void searchLocation(Request request) {
    try {
      Response response = locationDao.search(request.getRequest());
      sender().tell(response, self());
      SearchDTO searchDto = Util.createSearchDto(request.getRequest());
      String[] types = {ProjectUtil.EsType.location.getTypeName()};
      generateSearchTelemetryEvent(searchDto, types, response.getResult());
    } catch (Exception ex) {
      ProjectLogger.log(ex.getMessage(), ex);
      sender().tell(ex, self());
    }
  }

  private void deleteLocation(Request request) {
    try {
      String locationId = (String) request.getRequest().get(JsonKey.LOCATION_ID);
      LocationRequestValidator.isLocationHasChild(locationId);
      Response response = locationDao.delete(locationId);
      sender().tell(response, self());
      ProjectLogger.log("Delete location data from ES");
      deleteDataFromES(locationId);
      generateTelemetryForLocation(locationId, new HashMap<>(), JsonKey.DELETE);
    } catch (Exception ex) {
      ProjectLogger.log(ex.getMessage(), ex);
      sender().tell(ex, self());
    }
  }

  private void saveDataToES(Map<String, Object> locData, String opType) {
    Request request = new Request();
    request.setOperation(LocationActorOperation.UPSERT_LOCATION_TO_ES.getValue());
    request.getRequest().put(JsonKey.LOCATION, locData);
    request.getRequest().put(JsonKey.OPERATION_TYPE, opType);
    try {
      tellToAnother(request);
    } catch (Exception ex) {
      ProjectLogger.log("Exception Ocurred during saving location data to ES : ", ex);
    }
  }

  private void deleteDataFromES(String locId) {
    Request request = new Request();
    request.setOperation(LocationActorOperation.DELETE_LOCATION_FROM_ES.getValue());
    request.getRequest().put(JsonKey.LOCATION_ID, locId);
    try {
      tellToAnother(request);
    } catch (Exception ex) {
      ProjectLogger.log("Exception Ocurred during saving location data to ES : ", ex);
    }
  }

  private void validateUpsertLocnReq(UpsertLocationRequest locationRequest, String operation) {
    if (StringUtils.isNotEmpty(locationRequest.getType())) {
      LocationRequestValidator.isValidLocationType(locationRequest.getType());
    }
    LocationRequestValidator.isValidParentIdAndCode(locationRequest, operation);
  }
}
