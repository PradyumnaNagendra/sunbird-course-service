package controllers.textbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import controllers.BaseApplicationTest;
import actors.DummyActor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.modules.junit4.PowerMockRunner;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import util.ACTOR_NAMES;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"jdk.internal.reflect.*", "javax.management.*"})
public class TextbookControllerTest extends BaseApplicationTest {

  private static ObjectMapper mapper = new ObjectMapper();
  private static String TEXTBOOK_ID = "textbookId";

  @Before
  public void before() {
    setup(ACTOR_NAMES.TEXTBOOK_TOC_ACTOR,DummyActor.class);
  }

  @Ignore
  @Test
  public void testUploadTocWithUrl() {
    Http.RequestBuilder req =
        new Http.RequestBuilder()
            .uri(
                "/v1/textbook/toc/upload/?fileUrl=https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/toc/do_1126526588628582401237/download.csv")
            .method("POST");
    Result result = Helpers.route(application, req);
    Assert.assertEquals( 200, result.status());
  }

  @Test
  public void testGetTocUrl() {
    Http.RequestBuilder req =
            new Http.RequestBuilder()
                    .uri(
                            "/v1/textbook/toc/download/"+TEXTBOOK_ID)
                    .method("GET");
    Result result = Helpers.route(application, req);
    Assert.assertEquals( 200, result.status());
  }
}
