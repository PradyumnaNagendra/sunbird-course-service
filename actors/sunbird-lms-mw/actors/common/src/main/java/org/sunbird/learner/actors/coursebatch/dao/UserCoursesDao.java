package org.sunbird.learner.actors.coursebatch.dao;

import java.util.Map;
import org.sunbird.common.models.response.Response;
import org.sunbird.models.user.courses.UserCourses;

public interface UserCoursesDao {

  /**
   * Get user courses information.
   *
   * @param id Identifier generated using courseId, batchId and userId
   * @return User courses information
   */
  UserCourses read(String id);

  /**
   * Create an entry for user courses information
   *
   * @param userCoursesDetails User courses information
   */
  Response insert(Map<String, Object> userCoursesDetails);

  /**
   * Update user courses information
   *
   * @param updateAttributes Map containing user courses attributes which needs to be updated
   */
  Response update(Map<String, Object> updateAttributes);
}
