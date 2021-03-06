package angular4J.remote;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import angular4J.api.NGCast;
import angular4J.api.NGCastIgnore;
import angular4J.api.NGCastMap;
import angular4J.context.NGSessionScopeContext;
import angular4J.log.NGLogger;
import angular4J.log.NGLogger.Level;
import angular4J.util.ModelQueryFactory;
import angular4J.util.ModelQueryImpl;
import angular4J.util.NGParser;
import angular4J.util.NGTypeMap;

/**
 * Angular4J RPC main handler.
 */

@SuppressWarnings("serial")
@ApplicationScoped
public class InvocationHandler implements Serializable {

   @Inject
   NGLogger logger;

   @Inject
   ModelQueryFactory modelQueryFactory;

   public void realTimeInvoke(Object ServiceToInvoque, String methodName, JsonObject params, RealTimeDataReceivedEvent event, long reqID, String UID) {

      NGSessionScopeContext.getInstance().setCurrentContext(UID);
      Map<String, Object> returns = new HashMap<>();
      returns.put("isRT", true);
      try {
         genericInvoke(ServiceToInvoque, methodName, params, returns, reqID, UID, null);

         if (returns.get("mainReturn") != null) {
            event.getConnection().write(NGParser.getInstance().serialize(returns), false);
         }
      }
      catch (SecurityException | ClassNotFoundException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException e) {
         e.printStackTrace();
      }
   }

   public Map<String, Object> invoke(Object o, String method, JsonObject params, String UID, HttpServletRequest request) {

      NGSessionScopeContext.getInstance().setCurrentContext(UID);
      Map<String, Object> returns = new HashMap<>();
      try {
         returns.put("isRT", false);
         genericInvoke(o, method, params, returns, 0, UID, request);
      }
      catch (Exception e) {
         e.printStackTrace();
      }
      return returns;
   }

   private Method getMethod(Class<?> clazz, String methodName, int paramSize) {
      for (Method m: clazz.getMethods()) {
         if (m.getName().equals(methodName) && m.getGenericParameterTypes().length == paramSize && !Modifier.isVolatile(m.getModifiers())) {
            return m;
         }
      }
      return null;
   }

   private Type getParamType(Object service, String id) {
      try {
         Field[] fields = service.getClass().getDeclaredFields();
         for (Field field: fields) {
            if (field.getType().isAssignableFrom(NGTypeMap.class)) {
               NGCastMap ann = field.getAnnotation(NGCastMap.class);
               if (ann != null) {
                  field.setAccessible(true);
                  NGTypeMap ngTypeMap = (NGTypeMap) field.get(service);
                  if (ngTypeMap != null) {
                     return ngTypeMap.getNGType(id).getType();
                  }
               }
            }
         }
      }
      catch (Exception e) {}

      return null;
   }

   private Type getParamCastType(Object service, Method m, Type param, int paramNumber) {
      if (!m.isAnnotationPresent(NGCastIgnore.class)) {
         Annotation[] annotations = m.getParameterAnnotations()[paramNumber];
         for (Annotation ann: annotations) {
            if (ann.annotationType().isAssignableFrom(NGCast.class)) {
               return this.getParamType(service, ((NGCast) ann).value());
            }
         }
      }
      return param;
   }

   private Object castParam(Object service, Type type, JsonElement element) {
      if (element.isJsonArray()) {
         return NGParser.getInstance().deserialize(element.getAsJsonArray(), type);
      } else {
         return NGParser.getInstance().deserialize(element, type);
      }
   }

   private void genericInvoke(Object service, String methodName, JsonObject params, Map<String, Object> returns, long reqID, String UID, HttpServletRequest request)
            throws SecurityException, ClassNotFoundException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException {

      Object mainReturn = null;
      Method m = null;
      JsonElement argsElem = params.get("args");

      if (reqID > 0) {
         returns.put("reqId", reqID);
      }
      if (argsElem != null) {

         JsonArray args = params.get("args").getAsJsonArray();
         m = this.getMethod(service.getClass(), methodName, args.size());

         Type[] parameters = m.getGenericParameterTypes();
         if (parameters.length == args.size()) {
            List<Object> argsValues = new ArrayList<>();
            for (int i = 0; i < parameters.length; i++) {
               argsValues.add(this.castParam(service, this.getParamCastType(service, m, parameters[i], i), args.get(i)));
            }
            try {
               mainReturn = m.invoke(service, argsValues.toArray());
            }
            catch (Exception e) {
               handleException(m, e);
               e.printStackTrace();
            }
         }
      } else {
         m = this.getMethod(service.getClass(), methodName, 0);
         try {
            mainReturn = m.invoke(service);
         }
         catch (Exception e) {}
      }

      ModelQueryImpl qImpl = (ModelQueryImpl) this.modelQueryFactory.get(service.getClass());
      Map<String, Object> scMap = new HashMap<>(qImpl.getData());
      returns.putAll(scMap);
      qImpl.getData().clear();
      if (!this.modelQueryFactory.getRootScope().getRootScopeMap().isEmpty()) {
         returns.put("rootScope", new HashMap<>(this.modelQueryFactory.getRootScope().getRootScopeMap()));
         this.modelQueryFactory.getRootScope().getRootScopeMap().clear();
      }

      returns.put("mainReturn", mainReturn);

      if (!this.logger.getLogPool().isEmpty()) {
         returns.put("log", this.logger.getLogPool().toArray());
         this.logger.getLogPool().clear();
      }
   }

   private void handleException(Method m, Exception e) {
      Throwable cause = e.getCause();
      StringBuilder exceptionString = new StringBuilder(m.getName());
      exceptionString.append(" -->");
      exceptionString.append(cause.getClass().getName());

      if (cause.getMessage() != null) {
         exceptionString.append(" ").append(cause.getMessage());
      }

      this.logger.log(Level.ERROR, exceptionString.toString());
   }
}
