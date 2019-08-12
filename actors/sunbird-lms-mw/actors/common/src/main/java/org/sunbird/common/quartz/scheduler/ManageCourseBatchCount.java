/** */
package org.sunbird.common.quartz.scheduler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.MapUtils;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.learner.actors.coursebatch.CourseEnrollmentActor;
import org.sunbird.learner.util.CourseBatchSchedulerUtil;
import org.sunbird.learner.util.Util;
import org.sunbird.telemetry.util.TelemetryUtil;

/**
 * This class will update course batch count in EKStep.
 *
 * @author Manzarul
 */
public class ManageCourseBatchCount implements Job {

  private Map<String, Map<String, Object>> courseDetailsMap = new HashMap<>();
  private Map<String, List<Map<String, Object>>> openBatchMap = new HashMap<>();
  private Map<String, List<Map<String, Object>>> privateBatchMap = new HashMap<>();

  @SuppressWarnings("unchecked")
  public void execute(JobExecutionContext ctx) throws JobExecutionException {
    ProjectLogger.log(
        "Executing COURSE_BATCH_COUNT job at: "
            + Calendar.getInstance().getTime()
            + " triggered by: "
            + ctx.getJobDetail().toString());
    Util.initializeContextForSchedulerJob(
        JsonKey.SYSTEM, ctx.getFireInstanceId(), JsonKey.SCHEDULER_JOB);
    Map<String, Object> logInfo =
        genarateLogInfo(JsonKey.SYSTEM, ctx.getJobDetail().getDescription());
    logInfo.put("LOG_LEVEL", "info");
    // Collect all those batches from ES whose start date is today and
    // countIncrementStatus value is
    // false.
    // and all those batches whose end date was yesterday and countDecrementStatus
    // value is false.
    // update the countIncrement or decrement status value as true , countIncrement
    // or decrement
    // date as today date.
    // make the status in course batch table based on course start or end - in case
    // of start make it
    // 1 , for end make it 2.
    // now update the data into cassandra and ES both and EKStep content with count
    // increment and
    // decrement value.
    SimpleDateFormat format = new SimpleDateFormat(ProjectUtil.YEAR_MONTH_DATE_FORMAT);
    Calendar cal = Calendar.getInstance();
    String today = "";
    String yesterDay = "";
    today = format.format(cal.getTime());
    cal.add(Calendar.DATE, -1);
    yesterDay = format.format(cal.getTime());
    ProjectLogger.log(
        "start date and end date is ==" + today + "  " + yesterDay, LoggerEnum.INFO.name());
    Map<String, Object> data = CourseBatchSchedulerUtil.getBatchDetailsFromES(today, yesterDay);
    if (data != null && data.size() > 0) {
      if (null != data.get(JsonKey.START_DATE)) {
        List<Map<String, Object>> listMap =
            (List<Map<String, Object>>) data.get(JsonKey.START_DATE);
        for (Map<String, Object> map : listMap) {
          updateCourseBatchStatus(true, false, map);
          updateCourseIdToBatchListMap(map);
        }
        handleUpdateBatchCount(true);
      }
      if (null != data.get(JsonKey.END_DATE)) {
        List<Map<String, Object>> listMap = (List<Map<String, Object>>) data.get(JsonKey.END_DATE);
        for (Map<String, Object> map : listMap) {
          updateCourseBatchStatus(false, true, map);
          updateCourseIdToBatchListMap(map);
        }
        handleUpdateBatchCount(false);
      }

      if (null != data.get(JsonKey.STATUS)) {
        List<Map<String, Object>> listMap = (List<Map<String, Object>>) data.get(JsonKey.STATUS);
        for (Map<String, Object> map : listMap) {
          updateCourseBatchStatus(false, false, map);
          boolean flag = CourseBatchSchedulerUtil.updateDataIntoES(map);
          if (flag) {
            CourseBatchSchedulerUtil.updateDataIntoCassandra(map);
          }
        }
      }
    } else {
      ProjectLogger.log(
          "No data found in Elasticsearch for course batch update.", LoggerEnum.INFO.name());
    }
    TelemetryUtil.telemetryProcessingCall(logInfo, null, null, "LOG");
  }

  private Map<String, Object> genarateLogInfo(String logType, String message) {

    Map<String, Object> info = new HashMap<>();
    info.put(JsonKey.LOG_TYPE, logType);
    long startTime = System.currentTimeMillis();
    info.put(JsonKey.START_TIME, startTime);
    info.put(JsonKey.MESSAGE, message);
    info.put(JsonKey.LOG_LEVEL, JsonKey.INFO);

    return info;
  }

  private void handleUpdateBatchCount(boolean increment) {
    updateCourseDetailsMap(openBatchMap);
    updateCourseDetailsMap(privateBatchMap);
    updateAllCourseBatchCount(increment);
    openBatchMap.clear();
    privateBatchMap.clear();
    courseDetailsMap.clear();
  }

  private void updateCourseBatchStatus(
      boolean isCountIncrementStatus,
      boolean isCountDecrementStatus,
      Map<String, Object> courseBatchMap) {
    courseBatchMap.put(JsonKey.STATUS, ProjectUtil.ProgressStatus.STARTED.getValue());
    if (isCountIncrementStatus) {
      courseBatchMap.put(JsonKey.COUNTER_INCREMENT_STATUS, true);
      courseBatchMap.put(JsonKey.COUNT_INCREMENT_DATE, ProjectUtil.getFormattedDate());
    }
    if (isCountDecrementStatus) {
      courseBatchMap.put(JsonKey.COUNTER_DECREMENT_STATUS, true);
      courseBatchMap.put(JsonKey.COUNT_DECREMENT_DATE, ProjectUtil.getFormattedDate());
      courseBatchMap.put(JsonKey.STATUS, ProjectUtil.ProgressStatus.COMPLETED.getValue());
    }
    courseBatchMap.put(JsonKey.UPDATED_DATE, ProjectUtil.getFormattedDate());
  }

  private void updateCourseIdToBatchListMap(Map<String, Object> map) {
    String courseId = (String) map.get(JsonKey.COURSE_ID);
    String enrollmentType = (String) map.get(JsonKey.ENROLLMENT_TYPE);
    if (JsonKey.OPEN.equals(enrollmentType)) {
      updateBatchMap(courseId, map, openBatchMap);
    } else {
      updateBatchMap(courseId, map, privateBatchMap);
    }
  }

  private void updateBatchMap(
      String courseId, Map<String, Object> map, Map<String, List<Map<String, Object>>> batchMap) {
    if (batchMap.containsKey(courseId)) {
      batchMap.get(courseId).add(map);
    } else {
      List<Map<String, Object>> batchList = new ArrayList<>();
      batchList.add(map);
      batchMap.put(courseId, batchList);
    }
  }

  private void updateAllCourseBatchCount(boolean increment) {
    for (Map.Entry<String, List<Map<String, Object>>> openBatchList : openBatchMap.entrySet()) {
      String contentName = CourseBatchSchedulerUtil.getCountName(JsonKey.OPEN);
      updateCourseBatchCount(
          increment, contentName, openBatchList.getKey(), openBatchList.getValue().size());
    }
    for (Map.Entry<String, List<Map<String, Object>>> privateBatchList :
        privateBatchMap.entrySet()) {
      String contentName = CourseBatchSchedulerUtil.getCountName(JsonKey.INVITE_ONLY);
      updateCourseBatchCount(
          increment, contentName, privateBatchList.getKey(), privateBatchList.getValue().size());
    }
    doUpdateOpenAndClosedCourseBatchCount();
  }

  private void updateCourseBatchCount(
      boolean increment, String contentName, String courseId, int size) {
    int val = (int) courseDetailsMap.get(courseId).getOrDefault(contentName, 0);
    if (increment) {
      val += size;
    } else {
      val -= size;
      if (val < 0) {
        val = 0;
      }
    }
    courseDetailsMap.get(courseId).put(contentName, val);
  }

  private void updateCourseDetailsMap(Map<String, List<Map<String, Object>>> batchMap) {
    Map<String, String> ekstepHeader = CourseBatchSchedulerUtil.headerMap;
    for (Map.Entry<String, List<Map<String, Object>>> batchList : batchMap.entrySet()) {
      String courseId = batchList.getKey();
      if (!courseDetailsMap.containsKey(courseId)) {
        Map<String, Object> ekStepContent =
            CourseEnrollmentActor.getCourseObjectFromEkStep(courseId, ekstepHeader);
        if (MapUtils.isNotEmpty(ekStepContent)) {
          courseDetailsMap.put(courseId, ekStepContent);
        } else {
          ProjectLogger.log(
              "ManagerCourseBatchCount: updateDetailsMap: No content Found for courseId "
                  + courseId,
              LoggerEnum.INFO);
        }
      }
    }
  }

  private void doUpdateOpenAndClosedCourseBatchCount() {
    for (Map.Entry<String, Map<String, Object>> courseDetailsEntry : courseDetailsMap.entrySet()) {
      String courseId = courseDetailsEntry.getKey();
      doUpdateCourseBatchCount(
          openBatchMap.get(courseId), courseDetailsEntry.getValue(), courseId, JsonKey.OPEN);
      doUpdateCourseBatchCount(
          privateBatchMap.get(courseId),
          courseDetailsEntry.getValue(),
          courseId,
          JsonKey.INVITE_ONLY);
    }
  }

  private void doUpdateCourseBatchCount(
      List<Map<String, Object>> batchMapList,
      Map<String, Object> contentDetails,
      String courseId,
      String enrollmentType) {
    if (batchMapList != null && !batchMapList.isEmpty()) {
      String contentName = CourseBatchSchedulerUtil.getCountName(enrollmentType);
      boolean response =
          CourseBatchSchedulerUtil.updateEkstepContent(
              courseId, contentName, (int) contentDetails.get(contentName));
      if (response) {
        batchMapList.forEach(
            map -> {
              if (CourseBatchSchedulerUtil.updateDataIntoES(map)) {
                CourseBatchSchedulerUtil.updateDataIntoCassandra(map);
              }
            });
      } else {
        ProjectLogger.log(
            "ManagerCourseBatchCount: updateDetailsMap: Update count failed for courseId "
                + courseId,
            LoggerEnum.INFO);
      }
    }
  }
}
