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


package org.openqa.grid.web.servlet.custom;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.selenium.remote.CapabilityType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class CustomServlet extends RegistryBasedServlet {

  public CustomServlet() {
    super(null);
  }

  public CustomServlet(Registry registry) {
    super(registry);
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    process(request, response);
  }

  protected void process(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setStatus(200);
    JsonObject res;
    try {
      res = getResponse(request);
      response.getWriter().print(res);
      response.getWriter().close();
    } catch (JsonSyntaxException e) {
      throw new GridException(e.getMessage());
    }

  }

  private JsonObject getResponse(HttpServletRequest request) throws IOException {
    JsonObject res = new JsonObject();
    res.addProperty("success", true);
    try {
      if (request.getInputStream() != null) {
        JsonObject requestJSON = getRequestJSON(request);
        List<String> keysToReturn = null;

        if (request.getParameter("configuration") != null && !"".equals(request.getParameter("configuration"))) {
          keysToReturn = Arrays.asList(request.getParameter("configuration").split(","));
        } else if (requestJSON != null && requestJSON.has("configuration")) {
          keysToReturn = new Gson().fromJson(requestJSON.getAsJsonArray("configuration"), ArrayList.class);
        }

        Registry registry = getRegistry();
        JsonElement config = registry.getConfiguration().toJson();
        for (Map.Entry<String, JsonElement> entry : config.getAsJsonObject().entrySet()) {
          if (keysToReturn == null || keysToReturn.isEmpty() || keysToReturn.contains(entry.getKey())) {
            res.add(entry.getKey(), entry.getValue());
          }
        }

        if (keysToReturn == null || keysToReturn.isEmpty() || keysToReturn.contains("browserSlotCounts")) {
          res.add("browserSlotCounts", getBrowserSlotCounts());
        }

        if (keysToReturn != null && keysToReturn.contains("enableNewSessionToProxy")) {
          res.add("enableNewSessionToProxy", enableNewSessionToProxy(keysToReturn.get(1)));
        }

        if (keysToReturn != null && keysToReturn.contains("disableNewSessionToProxy")) {
          res.add("disableNewSessionToProxy", disableNewSessionToProxy(keysToReturn.get(1)));
        }

        if (keysToReturn != null && keysToReturn.contains("getNewSessionDisabledProxies")) {
          res.add("getNewSessionDisabledProxies", getNewSessionDisabledProxies());
        }

        if (keysToReturn != null && keysToReturn.contains("bashCommandToFarmNode")) {
          res.add("bashCommandToFarmNode", bashCommandToFarmNode(keysToReturn));
        }

      }
    } catch (Exception e) {
      res.remove("success");
      res.addProperty("success", false);
      res.addProperty("msg", e.getMessage());
    }
    return res;

  }

  private JsonObject enableNewSessionToProxy(String proxyId) {
    getRegistry().getAllProxies().enableNewSessionToProxy(proxyId);
    JsonObject result = new JsonObject();
    result.addProperty("new session enabled for proxy", proxyId);
    return result;
  }

  private JsonObject disableNewSessionToProxy(String proxyId) {
    getRegistry().getAllProxies().disableNewSessionToProxy(proxyId);
    JsonObject result = new JsonObject();
    result.addProperty("new session disabled for proxy", proxyId);
    return result;
  }

  private JsonObject getNewSessionDisabledProxies(){
    JsonObject result = new JsonObject();
    List<String> list = getRegistry().getAllProxies().getNewSessionDisabledProxies();
    for(String proxyId : list){
      result.addProperty("new session disabled for proxy", proxyId);
    }
    result.addProperty("total", list.size());
    return result;
  }

  private JsonObject bashCommandToFarmNode(List<String> keysToReturn){
    JsonObject result = new JsonObject();
    String farmNodeProxyId = keysToReturn.get(1);
    String farmCommand = keysToReturn.get(2);
    if(farmNodeProxyId == null || farmCommand == null) {
      result.addProperty("farmNodeProxyId",farmNodeProxyId);
      result.addProperty("farmCommand",farmCommand);
      result.addProperty("status","failed");
      return  result;
    }

    URL remoteURL = getRegistry().getProxyById(farmNodeProxyId).getRemoteHost();
    if(remoteURL == null){
      result.addProperty("remoteURL","not found");
      result.addProperty("status","failed");
      return  result;
    }

    StringBuilder farmRequest = new StringBuilder();
    farmRequest.append("http://").append(remoteURL.getHost()).append(":").append(remoteURL.getPort());
    farmRequest.append("/extra/NodeCmdServlet?configuration=bash,-c,");
    try {
      farmRequest.append(URLEncoder.encode(farmCommand,"UTF-8"));
    } catch (UnsupportedEncodingException e) {
      result.addProperty("commandEncodeError",e.getMessage());
      result.addProperty("status","failed");
      return  result;
    }
    result = sendHttpGet(farmRequest.toString());
    result.addProperty("farmRequest", farmRequest.toString());
    return result;
  }

  private static JsonObject sendHttpGet(String request) {
    JsonObject result = new JsonObject();
    try {
      CloseableHttpClient httpClient = HttpClientBuilder.create().build();
      HttpGet httpGet = new HttpGet(request);
      CloseableHttpResponse response = httpClient.execute(httpGet);
      //System.out.println(EntityUtils.toString(response.getEntity()));
      return new JsonParser().parse(EntityUtils.toString(response.getEntity())).getAsJsonObject();
    } catch (Exception e) {
      //System.out.println("CustomServlet, sendHttpGet error: " + e.getMessage());
      result.addProperty("farmRequest", request);
      result.addProperty("sendHttpGetError", e.getMessage());
      return result;
    }
  }

  private JsonObject getBrowserSlotCounts() {
    int freeSlots = 0;
    int totalSlots = 0;

    Map<String, Integer> freeBrowserSlots = new HashMap<>();
    Map<String, Integer> totalBrowserSlots = new HashMap<>();

    for (RemoteProxy proxy : getRegistry().getAllProxies()) {
      for (TestSlot slot : proxy.getTestSlots()) {
        String
          slot_browser_name =
          slot.getCapabilities().get(CapabilityType.BROWSER_NAME).toString().toUpperCase();
        if (slot.getSession() == null) {
          if (freeBrowserSlots.containsKey(slot_browser_name)) {
            freeBrowserSlots.put(slot_browser_name, freeBrowserSlots.get(slot_browser_name) + 1);
          } else {
            freeBrowserSlots.put(slot_browser_name, 1);
          }
          freeSlots += 1;
        }
        if (totalBrowserSlots.containsKey(slot_browser_name)) {
          totalBrowserSlots.put(slot_browser_name, totalBrowserSlots.get(slot_browser_name) + 1);
        } else {
          totalBrowserSlots.put(slot_browser_name, 1);
        }
        totalSlots += 1;
      }
    }

    JsonObject result = new JsonObject();

    for (String str : totalBrowserSlots.keySet()) {
      JsonObject browser = new JsonObject();
      browser.addProperty("total", totalBrowserSlots.get(str));
      if (freeBrowserSlots.containsKey(str)) {
        browser.addProperty("free", freeBrowserSlots.get(str));
      } else {
        browser.addProperty("free", 0);
      }
      result.add(str, browser);
    }

    result.addProperty("total", totalSlots);
    result.addProperty("total_free", freeSlots);
    return result;
  }

  private JsonObject getRequestJSON(HttpServletRequest request) throws IOException {
    JsonObject requestJSON = null;
    BufferedReader rd = new BufferedReader(new InputStreamReader(request.getInputStream()));
    StringBuilder s = new StringBuilder();
    String line;
    while ((line = rd.readLine()) != null) {
      s.append(line);
    }
    rd.close();
    String json = s.toString();
    if (!"".equals(json)) {
      requestJSON = new JsonParser().parse(json).getAsJsonObject();
    }
    return requestJSON;
  }
}
