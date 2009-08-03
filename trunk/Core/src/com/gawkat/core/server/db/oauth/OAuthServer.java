package com.gawkat.core.server.db.oauth;


import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.mail.Session;

import com.gawkat.core.client.account.UserData;
import com.gawkat.core.client.oauth.OAuthTokenData;
import com.gawkat.core.server.jdo.data.Session_AccessTokenJdo;
import com.gawkat.core.server.jdo.data.Session_NonceJdo;
import com.gawkat.core.server.jdo.data.ThingJdo;
import com.gawkat.core.server.jdo.data.Thing_TypeJdo;

public class OAuthServer {

  final private static int APPLICATION = 1;
  final private static int USER = 2;
  
  /**
   * constructor
   */
  public OAuthServer() {
  }

  /**
   * use this to debug
   * 
   * @param debug
   */
  private void debug(String debug) {
    System.out.println(debug);
  }
  
  /**
   * A. -> (B. grant request token): Can we go to the next step? Service
   * Provider (this), can grant request token?
   * 
   * NOTE: using rpc, I am only using the app base url, to sign, and no port
   * 
   * @param token
   * @return
   */
  public OAuthTokenData requestToken(OAuthTokenData token, String url) {

    debug("requestToken: token: " + token.toString() + " url: " + url);

    // get the application data according to the consumerKey Given, then lets
    // see if it matches up
    ApplicationData appData = getApplicationId(token);

    // verify the signed signature from the client matches the local
    boolean verifySignature = token.verify(url, appData.consumerSecret);

    // check nonce
    boolean verifyNonce = verifyNonceHasntBeenUsed(token, (long) Thing_TypeJdo.TYPE_APPLICATION, appData.applicationId);

    // prepare for transport back
    OAuthTokenData returnToken = new OAuthTokenData();

    // examine if we can go to the next step
    if (appData.applicationId == 0 | verifySignature == false | verifyNonce == false) {
      returnToken.setResult(OAuthTokenData.ERROR);
    } else {
      returnToken.setResult(OAuthTokenData.SUCCESS);
    }

    // on success - grant request token access
    AccessTokenData at = new AccessTokenData();
    if (returnToken.getResult() == OAuthTokenData.SUCCESS) {
      at = setAccessToken_application(appData.applicationId);
      returnToken.setAccessToken(at.accessToken, at.accessTokenSecret);
    }

    // set nonce, so it can't be used again
    setNonce(token, url, (long) Thing_TypeJdo.TYPE_APPLICATION, appData.applicationId);

    debug("requestToken: url: " + url + " secret: " + at.accessTokenSecret);

    try {
      returnToken.sign(url, at.accessTokenSecret);
    } catch (Exception e) {
      debug("requestToken: ****** ERROR SIGNING");
      e.printStackTrace();
    }

    // transport back to client
    return returnToken;
  }

  /**
   * get user Access Token
   * 
   * TODO - I am going to have to come back and rewrite this later. This isn't
   * correct yet. Need more awakeness. :)
   * 
   * 
   * @param appAccessToken  - apps access token with users key and secret transfer app access
   *          to user access
   * @param url
   * @return
   */
  public OAuthTokenData getUserAccessToken(OAuthTokenData appAccessToken, String url) {

    // debug
    debug("getAccessToken: token: " + appAccessToken.toString() + " url: " + url);

    // get userData to verify agianst - this will also tell if the user exists
    UserData userData = getUserData(appAccessToken);

    // verify the signed signature from the client matches the local
    // TODO - should I be verifying with consumerSecret?
    boolean verifySignature = appAccessToken.verify(url, userData.consumerSecret);

    // check nonce
    boolean verifyNonce = verifyNonceHasntBeenUsed(appAccessToken, (long) Thing_TypeJdo.TYPE_USER, userData.userId);

    // verify application's access, then transfer it to the user if all passes
    Long accessId = getAccessId(appAccessToken);

    // examine if we can go to the next step
    if (accessId == 0 | userData.userId == 0 | userData.error > 0 | verifySignature == false | verifyNonce == false) {
      appAccessToken.setResult(OAuthTokenData.ERROR);
    } else {
      appAccessToken.setResult(OAuthTokenData.SUCCESS);
    }

    // change access token session to user
    if (appAccessToken.getResult() == OAuthTokenData.SUCCESS) {
      setAccessToken_user(accessId, userData.userId);
    } else {
      // TODO - what to do, what to do?
    }

    // set nonce, so it can't be used again.
    setNonce(appAccessToken, url, (long) Thing_TypeJdo.TYPE_USER, userData.userId);

    // sign the token for transport back
    // TODO should I be signing with consumer secret?
    try {
      appAccessToken.sign(url, userData.consumerSecret);
    } catch (Exception e) {
      debug("requestToken: ****** ERROR SIGNING");
      e.printStackTrace();
    }

    // if we make it this far, it becomes userAccessToken
    // this is just for looks
    OAuthTokenData userAccessToken = appAccessToken;

    // transport back to client
    return userAccessToken;
  }

  /**
   * find the user from consumerKey and consumerSecret also finds if the user
   * actually exists will use it to verify the signing
   * 
   * @param token
   * @return
   */
  private UserData getUserData(OAuthTokenData token) {

    String consumerKey = token.getConsumerKey();

    // get user (thing)
    ThingJdo[] users = ThingJdo.query((long)Thing_TypeJdo.TYPE_USER, consumerKey);
    Long userId = users[0].getId();
    String consumerSecret = users[0].getSecret();
    
    // found a user
    UserData ud = new UserData();
    ud.consumerKey = token.getConsumerKey();
    ud.consumerSecret = consumerSecret;
    ud.userId = userId;

    return ud;
  }

  /**
   * get the applications id - from app consumer key
   * 
   * @param token
   * @return
   */
  private ApplicationData getApplicationId(OAuthTokenData token) {

    if (token == null) {
      System.out.println("No tokenData exists. ERROR: getApplicationId()");
    }

    String ck = token.getConsumerKey();
    
    // get user (thing)
    ThingJdo[] apps = ThingJdo.query((long) Thing_TypeJdo.TYPE_APPLICATION, ck);
    Long applicationId = apps[0].getId();
    String consumerKey = apps[0].getKey();
    String consumerSecret = apps[0].getSecret();
    
    ApplicationData appData = new ApplicationData();
    appData.applicationId = applicationId;
    appData.consumerKey = consumerKey;
    appData.consumerSecret = consumerSecret;
      
    return appData;
  }

  /**
   * verify this nonce has not been used before nonce - 30 chars, and is unique
   * for each request
   * 
   * @param token - token sent from client
   * @param thingTypeid 
   * @param thingId 
   * @return
   */
  private boolean verifyNonceHasntBeenUsed(OAuthTokenData token, Long thingTypeId, Long thingId) {

    String whereQuery = "(ThingTypeID='" + thingTypeId + "') AND (ThingId='" + thingId + "')";
   
    if (whereQuery.length() == 0) {
      System.out.println("ERROR: verifyNonce(), for some reason there is no appId or userId");
      return false;
    }
    
    String nonce = token.getNonce();

    boolean found = Session_NonceJdo.doesNonceExist(thingTypeId, thingId, nonce);
 
    return found;
  }

  /**
   * record nonce that has been used
   * 
   * @param token
   * @param url
   * @param thingTypeId
   * @param thingId
   */
  private void setNonce(OAuthTokenData token, String url, Long thingTypeId, Long thingId) {
    Session_NonceJdo n = new Session_NonceJdo();
    n.insert(url, thingTypeId, thingId, token.getNonce());
  }

  /**
   * set a unique access token for the session for the application
   * 
   * @param applicationId
   * @return
   */
  private AccessTokenData setAccessToken_application(Long applicationId) {

    String accessKey = getAccessKey();
    String accessSecret = getAccessSecret();

    Session_AccessTokenJdo sa = new Session_AccessTokenJdo();
    boolean success = sa.insert((long)Thing_TypeJdo.TYPE_APPLICATION, applicationId, accessKey, accessSecret);
    
    AccessTokenData at = new AccessTokenData();
    if (success == true) {
      at.accessToken = accessKey;
      at.accessTokenSecret = accessSecret;
    } else {
      at = null;
    }
    
    return at;
  }

  /**
   * get the AccessId - verify there is a session token
   * 
   * @param appAccessToken
   * @return
   */
  private Long getAccessId(OAuthTokenData appAccessToken) {

    String appConsumerKey = appAccessToken.getAccessToken_key();
    String appConsumerSecret = appAccessToken.getAccessToken_secret();

    Session_AccessTokenJdo[] sas = Session_AccessTokenJdo.query(appConsumerKey, appConsumerSecret);
    Session_AccessTokenJdo sa = sas[0];
    
    Long accessId = sa.getId();

    return accessId;
  }

  /**
   * set the access token for the session, but then then transfer it to this
   * user here
   * 
   * @param id - the unique data object id for that token
   * @param userId
   * @return
   */
  private boolean setAccessToken_user(Long id, Long userId) {

    return Session_AccessTokenJdo.updateAccessToken(id, userId);
     
  }

  /**
   * get accessToken (key) Creates a unique AccessKey for the session
   * 
   * The goal here is to have a unique Key and Secret for the Session which the
   * App or User can own for the session. Everything builds up to this.
   * 
   * @return
   */
  private String getAccessKey() {
    UUID uId = UUID.randomUUID();
    return uId.toString();
  }

  /**
   * get accesstoken (secret) Creates a unique Secret for teh session
   * 
   * The goal here is to have a unique Key and Secret for the Session which the
   * App or User can own for the session. Everything builds up to this.
   * 
   * @return
   */
  private String getAccessSecret() {

    int nounceLength = 15;
    String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz";
    String s = "";
    for (int i = 0; i < nounceLength; i++) {
      int rnum = (int) Math.floor(Math.random() * chars.length());
      s += chars.substring(rnum, rnum + 1);
    }
    return s;

  }

  // TODO
  private boolean verifyAccessToken(int applicationId, String accessToken, String accessTokenSecret) {
    return false;
  }

}