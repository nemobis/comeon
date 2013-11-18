package comeon.mediawiki;

import in.yuvi.http.fluent.ProgressListener;

import java.io.IOException;
import java.io.InputStream;

import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreProtocolPNames;
import org.mediawiki.api.MWApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Files;
import comeon.model.Picture;
import comeon.model.User;
import comeon.model.Wiki;
import comeon.ui.UI;

public final class MediaWikiImpl implements MediaWiki {

  private static final Logger LOGGER = LoggerFactory.getLogger(MediaWikiImpl.class);
  
  private static final DefaultHttpClient HTTP_CLIENT = new DefaultHttpClient();
  static {
    final String userAgentString = UI.BUNDLE.getString("useragent");
    LOGGER.info("ComeOn! uses \"{}\" as User-Agent", userAgentString);
    HTTP_CLIENT.getParams().setParameter(CoreProtocolPNames.USER_AGENT, userAgentString);
  }
  
  private final MWApi api;

  private final Wiki wiki;
  
  public MediaWikiImpl(final Wiki wiki) {
    this.wiki = wiki;
    // XXX MWApi requires AbstractHttpClient instead of HttpClient
    this.api = new MWApi(wiki.getUrl(), HTTP_CLIENT);
  }

  /*
   * (non-Javadoc)
   * 
   * @see comeon.commons.Commons#login()
   */
  @Override
  public void login() throws NotLoggedInException, FailedLoginException {
    try {
      final User user = wiki.getUser();
      final String result = this.api.login(user.getLogin(), user.getPassword());
      if (!this.api.isLoggedIn) {
        throw new NotLoggedInException(result);
      }
    } catch (final IOException e) {
      throw new FailedLoginException(e);
    }
  }

  @Override
  public boolean isLoggedIn() {
    return api.isLoggedIn;
  }
  
  /*
   * (non-Javadoc)
   * 
   * @see comeon.commons.Commons#upload(comeon.model.Picture,
   * in.yuvi.http.fluent.ProgressListener)
   */
  @Override
  public void upload(final Picture picture, final ProgressListener listener) throws NotLoggedInException,
      FailedLoginException, FailedUploadException, IOException {
    if (this.api.isLoggedIn) {
      final InputStream stream = Files.newInputStreamSupplier(picture.getFile()).getInput();
      try {
        this.api.upload(picture.getFile().getName(), stream, picture.getFile().length(), picture.getRenderedTemplate(),
            "Uploaded with ComeOn!", true, listener);
      } catch (final IOException e) {
        throw new FailedUploadException(e);
      }
    } else {
      this.login();
      this.upload(picture, listener);
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see comeon.commons.Commons#logout()
   */
  @Override
  public void logout() throws FailedLogoutException {
    if (this.api.isLoggedIn) {
      try {
        this.api.logout();
      } catch (final IOException e) {
        throw new FailedLogoutException(e);
      }
    }
  }
}