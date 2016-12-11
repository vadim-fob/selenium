// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.grid.selenium.proxy;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.SeleniumProtocol;
import org.openqa.grid.common.exception.RemoteException;
import org.openqa.grid.common.exception.RemoteNotReachableException;
import org.openqa.grid.common.exception.RemoteUnregisterException;
import org.openqa.grid.internal.BaseRemoteProxy;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.listeners.CommandListener;
import org.openqa.grid.internal.listeners.SelfHealingProxy;
import org.openqa.grid.internal.listeners.TestSessionListener;
import org.openqa.grid.internal.listeners.TimeoutListener;
import org.openqa.grid.internal.utils.HtmlRenderer;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.CapabilityType;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Default remote proxy for selenium, handling both selenium1 and webdriver requests.
 */
public class DefaultRemoteProxy extends BaseRemoteProxy
    implements
      TimeoutListener,
      SelfHealingProxy,
      CommandListener,
      TestSessionListener {

  private static final Logger LOG = Logger.getLogger(DefaultRemoteProxy.class.getName());

  public static final int DEFAULT_POLLING_INTERVAL = 10000;
  public static final int DEFAULT_UNREGISTER_DELAY = 60000;
  public static final int DEFAULT_DOWN_POLLING_LIMIT = 20;

  private volatile int pollingInterval = DEFAULT_POLLING_INTERVAL;
  private volatile int unregisterDelay = DEFAULT_UNREGISTER_DELAY;
  private volatile int downPollingLimit = DEFAULT_DOWN_POLLING_LIMIT;

  public DefaultRemoteProxy(RegistrationRequest request, Registry registry) {
    super(request, registry);

    pollingInterval = config.nodePolling != null ? config.nodePolling : DEFAULT_POLLING_INTERVAL;
    unregisterDelay = config.unregisterIfStillDownAfter != null ? config.unregisterIfStillDownAfter : DEFAULT_UNREGISTER_DELAY;
    downPollingLimit = config.downPollingLimit != null ? config.downPollingLimit : DEFAULT_DOWN_POLLING_LIMIT;
  }

  public void beforeRelease(TestSession session) {
    // release the resources remotely if the remote started a browser.
    if (session.getExternalKey() == null) {
      return;
    }
    boolean ok = session.sendDeleteSessionRequest();
    if (!ok) {
      LOG.warning("Error releasing the resources on timeout for session " + session);
    }
  }


  public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
    session.put("lastCommand", request.getMethod() + " - " + request.getPathInfo() + " executed.");
  }


  public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
    session.put("lastCommand", request.getMethod() + " - " + request.getPathInfo() + " executing ...");
  }

  private final HtmlRenderer renderer = new WebProxyHtmlRenderer(this);

  @Override
  public HtmlRenderer getHtmlRender() {
    return renderer;
  }

  /*
   * Self Healing part. Polls the remote, and marks it down if it cannot be reached twice in a row.
   */
  private volatile boolean down = false;
  private volatile boolean poll = true;

  // TODO freynaud
  private List<RemoteException> errors = new CopyOnWriteArrayList<>();
  private Thread pollingThread = null;

  public boolean isAlive() {
    try {
      getStatus();
      return true;
    } catch (Exception e) {
      LOG.fine("Failed to check status of node: " + e.getMessage());
      return false;
    }
  }

  public void startPolling() {
    pollingThread = new Thread(new Runnable() { // Thread safety reviewed
          int failedPollingTries = 0;
          long downSince = 0;

          public void run() {
            while (poll) {
              try {
                Thread.sleep(pollingInterval);
                if (!isAlive()) {
                  if (!down) {
                    failedPollingTries++;
                    if (failedPollingTries >= downPollingLimit) {
                      downSince = System.currentTimeMillis();
                      addNewEvent(new RemoteNotReachableException(String.format(
                        "Marking the node %s as down: cannot reach the node for %s tries",
                        DefaultRemoteProxy.this, failedPollingTries)));
                    }
                  } else {
                    long downFor = System.currentTimeMillis() - downSince;
                    if (downFor > unregisterDelay) {
                      addNewEvent(new RemoteUnregisterException(String.format(
                        "Unregistering the node %s because it's been down for %s milliseconds",
                        DefaultRemoteProxy.this, downFor)));
                    }
                  }
                } else {
                  down = false;
                  failedPollingTries = 0;
                  downSince = 0;
                }
              } catch (InterruptedException e) {
                return;
              }
            }
          }
        }, "RemoteProxy failure poller thread for " + getId());
    pollingThread.start();
  }

  public void stopPolling() {
    poll = false;
    pollingThread.interrupt();
  }

  public void addNewEvent(RemoteException event) {
    errors.add(event);
    onEvent(errors, event);

  }

  public void onEvent(List<RemoteException> events, RemoteException lastInserted) {
    for (RemoteException e : events) {
      if (e instanceof RemoteNotReachableException) {
        LOG.info(e.getMessage());
        down = true;
        this.errors.clear();
      }
      if (e instanceof RemoteUnregisterException) {
        LOG.info(e.getMessage());
        Registry registry = this.getRegistry();
        registry.removeIfPresent(this);
      }
    }
  }

  /**
   * overwrites the session allocation to discard the proxy that are down.
   */
  @Override
  public TestSession getNewSession(Map<String, Object> requestedCapability) {
    if (down) {
      return null;
    }
    return super.getNewSession(requestedCapability);
  }

  public boolean isDown() {
    return down;
  }

  /**
   * The client shouldn't have to care where firefox is installed as long as the correct version is
   * launched, however with webdriver the binary location is specified in the desiredCapability,
   * making it the responsibility of the person running the test.
   *
   * With this implementation of beforeSession, that problem disappears . If the webdriver slot is
   * registered with a firefox using a custom binary location, the hub will handle it.
   *
   * <p>
   * For instance if a node registers:
   * {"browserName":"firefox","version":"7.0","firefox_binary":"/home/ff7"}
   *
   * and later on a client requests {"browserName":"firefox","version":"7.0"} , the hub will
   * automatically append the correct binary path to the desiredCapability before it's forwarded to
   * the server. That way the version / install location mapping is done only once at the node
   * level.
   */
  public void beforeSession(TestSession session) {
    if (session.getSlot().getProtocol() == SeleniumProtocol.WebDriver) {
      Map<String, Object> cap = session.getRequestedCapabilities();

      if (BrowserType.FIREFOX.equals(cap.get(CapabilityType.BROWSER_NAME))) {
        if (session.getSlot().getCapabilities().get(FirefoxDriver.BINARY) != null
            && cap.get(FirefoxDriver.BINARY) == null) {
          session.getRequestedCapabilities().put(FirefoxDriver.BINARY,
              session.getSlot().getCapabilities().get(FirefoxDriver.BINARY));
        }
      }

      if (BrowserType.CHROME.equals(cap.get(CapabilityType.BROWSER_NAME))) {
        if (session.getSlot().getCapabilities().get("chrome_binary") != null) {
          Map<String, Object> options = (Map<String, Object>) cap.get(ChromeOptions.CAPABILITY);
          if (options == null) {
            options = new HashMap<>();
          }
          if (!options.containsKey("binary")) {
            options.put("binary", session.getSlot().getCapabilities().get("chrome_binary"));
          }
          cap.put(ChromeOptions.CAPABILITY, options);
        }
      }
    }
  }

  public void afterSession(TestSession session) {

    if(session.getExternalKey() == null) {
      LOG.info("session "+ session.toString() + " is not connected to remote. Skip after session");
      return;
    }

    try {
      String browserName = session.getSlot().getCapabilities().get("browserName").toString().toLowerCase();
      if( !(browserName.equals("android") || browserName.equals("ios")) ) {
        LOG.info("session "+ session.toString() + " browser name is " + browserName + ". Skip after session");
        return;
      }

    //TODO: config ,custom, get docker container name and pm2 process name, for Android
    //TODO: config, custom, get pm2 process name, for IOS

      LOG.info(" afterSession started");
      LOG.info(" session proxyId: " + getId());
      LOG.info("session slot remote url: "+ session.getSlot().getRemoteURL());    // http://172.17.0.6:4723/wd/hub
      LOG.info(" test slot browserName:" + browserName);
      String farmNodeAddressParameter = getConfig().custom.get("farmNodeAddress");
      LOG.info(" farmNodeParameter: " + farmNodeAddressParameter);
      String farmNodeAddress = getRegistry().getProxyById("http://"+farmNodeAddressParameter).getRemoteHost().toString();
      LOG.info(" farm node remote host: " + farmNodeAddress);

      String request = farmNodeAddress + "/extra/NodeCmdServlet?configuration=" + "runScript," + URLEncoder.encode("/restart-appium-"+ browserName +".sh", "UTF-8");
      LOG.info(" prepared request: "+request);
      LOG.info(" disableNewSessionToProxy .." + getId());
      getRegistry().getAllProxies().disableNewSessionToProxy(getId());
      runRestartAppium(session, request);

    } catch (Exception e) {
      LOG.warning(" after session event error: "+ e.getMessage());
    }
  }

  private void runRestartAppium(TestSession session, String request) {
    new Thread(new Runnable() {
      public void run() {
        restartAppium(session, request);
      }
    }).start();
  }

  private void restartAppium(TestSession session, String request){
    LOG.warning(" check that session has no ext key in registry ..");
    int times = 60;
    while(times != 0 || getRegistry().getSession(session.getExternalKey()) != null){
      try {
        //TODO: try without sleep
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      times--;
    }

    LOG.info(" send http get restart request...");
    sendHttpGet(request);
    LOG.info(" enableNewSessionToProxy " + getId());
    getRegistry().getAllProxies().enableNewSessionToProxy(getId());
    LOG.info(" restart is finished");
  }

  private void sendHttpGet(String request){
    try{
      CloseableHttpClient httpClient = HttpClientBuilder.create().build();
      HttpGet httpGet = new HttpGet(request);
      CloseableHttpResponse response = httpClient.execute(httpGet);
      LOG.info(" http get response: "+ EntityUtils.toString(response.getEntity()));
    } catch (Exception e){
      LOG.warning("DefaultRemoteProxy sendHttpGet failed: "+ e.getMessage());
    }
  }


  @Override
  public void teardown() {
    super.teardown();
    stopPolling();
  }
}
