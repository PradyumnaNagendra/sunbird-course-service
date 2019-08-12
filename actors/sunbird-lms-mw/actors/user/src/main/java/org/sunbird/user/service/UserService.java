package org.sunbird.user.service;

import akka.actor.ActorRef;
import java.util.Map;
import org.sunbird.common.request.Request;
import org.sunbird.models.user.User;

public interface UserService {

  Map<String, Object> esGetUserOrg(String userId, String orgId);

  User getUserById(String userId);

  void validateUserId(Request request);

  void validateUploader(Request request);

  Map<String, Object> esGetPublicUserProfileById(String userId);

  Map<String, Object> esGetPrivateUserProfileById(String userId);

  void syncUserProfile(
      String userId, Map<String, Object> userDataMap, Map<String, Object> userPrivateDataMap);

  String getValidatedCustodianOrgId(Map<String, Object> userMap, ActorRef actorRef);

  String getRootOrgIdFromChannel(String channel);

  String getCustodianChannel(Map<String, Object> userMap, ActorRef actorRef);
}
