package org.openqa.grid.web.servlet.devices;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.servlet.RegistryBasedServlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * For getting information from devices
 */
public class DevicesServlet extends RegistryBasedServlet {
  public DevicesServlet() {
    super(null);
  }

  public DevicesServlet(Registry registry) {
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

        if (keysToReturn == null || keysToReturn.isEmpty() || keysToReturn.contains("devicesInformation")) {
          res.add("devicesInformation", getDevicesInformation());
        }

      }
    } catch (Exception e) {
      res.remove("success");
      res.addProperty("success", false);
      res.addProperty("msg", e.getMessage());
    }
    return res;

  }

  private JsonObject getDevicesInformation(){
    JsonObject result = new JsonObject();
    JsonArray browser = new JsonArray();
    int x = 0;
    for (RemoteProxy proxy : getRegistry().getAllProxies()){
      for (TestSlot slot : proxy.getTestSlots()){
        browser.add(new Gson().toJsonTree(slot.getCapabilities()));
        browser.get(x).getAsJsonObject().remove("maxInstances");
        if (slot.getSession() == null) {
          browser.get(x).getAsJsonObject().addProperty("free", true);
        } else {
          browser.get(x).getAsJsonObject().addProperty("free", false);
        }
        x +=1;
      }

    }
    result.add("capabilities", browser);
    System.out.println("Returning device information.");

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
