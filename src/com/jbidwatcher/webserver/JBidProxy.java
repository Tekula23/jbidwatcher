package com.jbidwatcher.webserver;
/*
 * Copyright (c) 2000-2007, CyberFOX Software, Inc. All Rights Reserved.
 *
 * Developed by mrs (Morgan Schweers)
 */

import com.jbidwatcher.config.JConfig;
import com.jbidwatcher.util.html.JHTMLOutput;
import com.jbidwatcher.util.html.JHTMLDialog;
import com.jbidwatcher.util.html.HTMLDump;
import com.jbidwatcher.util.http.CookieJar;
import com.jbidwatcher.util.*;
import com.jbidwatcher.util.Currency;
import com.jbidwatcher.ui.JBidMouse;
import com.jbidwatcher.*;
import com.jbidwatcher.queue.MQFactory;
import com.jbidwatcher.xml.JTransformer;
import com.jbidwatcher.auction.AuctionsManager;
import com.jbidwatcher.auction.server.AuctionServerManager;
import com.jbidwatcher.auction.server.AuctionServer;
import com.jbidwatcher.auction.AuctionEntry;

import java.net.*;
import java.util.*;
import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

public class JBidProxy extends HTTPProxyClient {
  private static final String snipeCommand = "snipe?id=";
  private static final String addAuctionCommand = "addAuction?";
  private static final String activateSnipe = "activateSnipe?";
  private static final String cancelSnipe = "cancelSnipe?";
  private static final String findIDString = "id";
  private static final String findAmountString = "snipeamount";
  private static final String syndicate = "syndicate/";
  private static final String event = "event";
  private static final String messageFinisher = "<br>Return to <a href=\"" + Constants.PROGRAM_NAME + "\">auction list</a>.";

  private static List<String> _item_list = null;

  public JBidProxy(Socket talkSock) {
    super(talkSock);
    commonSetup();
    setName("JBidProxy");
  }

  private void commonSetup() {
    setServerName(Constants.PROGRAM_NAME + '/' + Constants.PROGRAM_VERS + " (Java)");
  }

  protected boolean needsAuthorization(String reqFile) {
    if(JConfig.queryConfiguration("allow.syndication", "true").equals("true")) {
      if(reqFile.charAt(0) == '/') {
        reqFile = reqFile.substring(1);
      }

      if(reqFile.startsWith(syndicate) || reqFile.endsWith(".jpg")) return false;
    }
    return true;
  }

  protected boolean handleAuthorization(String inAuth) {
    AuctionServer aucServ = AuctionServerManager.getInstance().getDefaultServer();
    String passcheck = aucServ.getUserId() + ':' + aucServ.getPassword();

    String converted = Base64.decodeToString(inAuth);
    return converted.equals(passcheck);
  }

  protected StringBuffer buildHeaders(String whatDocument, byte[][] buf) {
    String relativeDocument = whatDocument;
    StringBuffer outBuf = new StringBuffer(256);

    if(relativeDocument.charAt(0) == '/') {
      relativeDocument = relativeDocument.substring(1);
    }

    if(relativeDocument.endsWith(".jpg")) {
      dumpImage(relativeDocument, outBuf, buf);
      return outBuf;
    }
    if(relativeDocument.equals("synchronize") || relativeDocument.startsWith(syndicate)) {
      outBuf.append("Content-Type: text/xml\n");
    } else {
      outBuf.append("Content-Type: text/html; charset=UTF-8\n");
    }
    AuctionServer aucServ;

    AuctionEntry ae = AuctionsManager.getInstance().getEntry(relativeDocument);
    if(ae != null) {
      aucServ = ae.getServer();
    } else {
      aucServ = AuctionServerManager.getInstance().getDefaultServer();
    }

    CookieJar cj = aucServ.getNecessaryCookie(false);
    String newCookie = null;
    if(cj != null) newCookie = cj.toString();
    if(newCookie != null) {
      outBuf.append("Set-Cookie: ");
      outBuf.append(newCookie);
      outBuf.append('\n');
    }
    return outBuf;
  }

  private void dumpImage(String relativeDocument, StringBuffer outbuf, byte[][] buf) {
    String outPath = JConfig.queryConfiguration("auctions.savepath");
    String imgPath = outPath + System.getProperty("file.separator") + relativeDocument;
    File fp = new File(imgPath);
    if(fp.exists()) {
      outbuf.append("Content-Type: image/jpeg\n");
      outbuf.append("Content-Length: ").append(fp.length()).append('\n');
      cat(fp, buf);
    }
  }

  private void cat(File fp, byte[][] buf) {
    try {
      buf[0] = new byte[(int)fp.length()];
      FileInputStream fis = new FileInputStream(fp);
      int read = fis.read(buf[0], 0, (int)fp.length());
      if(read != fp.length()) ErrorManagement.logDebug("Couldn't read any data from " + fp.getName());
      fis.close();
    } catch(IOException e) {
      ErrorManagement.handleException("Can't read file " + fp.getName(), e);
    }
  }

  private StringBuffer setupSnipePage(String auctionId) {
    AuctionEntry ae = AuctionsManager.getInstance().getEntry(auctionId);

    if(ae==null) return(null);
    com.jbidwatcher.util.Currency minBid;

    try {
      minBid = ae.getCurBid().add(ae.getServer().getMinimumBidIncrement(ae.getCurBid(), ae.getNumBidders()));
    } catch(Currency.CurrencyTypeException ignored) {
      minBid = ae.getCurBid();
    }

    JHTMLOutput jho = new JHTMLOutput("Prepare snipe",
                                      new JHTMLDialog("Snipe", "./activateSnipe", "GET",
                                                      findIDString, auctionId, "snipe",
                                                      "Enter snipe amount, with no currency symbols.", findAmountString, 20,
                                                      minBid.getValueString()) +
                                                                               messageFinisher);

    return jho.getStringBuffer();
  }

  /**
   * Add Auction with AuctionId
   * @param auctionId the Id of the auction to be added
   */
  private void addAuction(String auctionId) {
    //search for auctionid
    //  What if this is -1?
    int pos = auctionId.indexOf("id=");
    String realId = auctionId.substring(pos+3);

    if(realId.indexOf('&') != -1) {
      realId = realId.substring(0, realId.indexOf('&'));
    }

    //Add new Auction to Auction Manager
    AuctionEntry auctionEntry = AuctionsManager.getInstance().newAuctionEntry(realId);

    if(auctionEntry != null) {
      AuctionsManager.getInstance().addEntry(auctionEntry);
    }
  }

  private String extractField(String fromCGI, String fieldName) {
    String genFieldname = fieldName + '=';

    int fieldLoc = fromCGI.indexOf(genFieldname);
    if(fieldLoc == -1) return null;

    String fieldData = fromCGI.substring(fieldLoc + genFieldname.length());

    int seperatorLoc = fieldData.indexOf("&");
    if(seperatorLoc != -1) {
      fieldData = fieldData.substring(0, seperatorLoc);
    }
    return fieldData;
  }

  private StringBuffer doSnipe(String snipeCGIText) {
    String auctionId = extractField(snipeCGIText, findIDString);
    String snipeAmount = extractField(snipeCGIText, findAmountString);

    AuctionEntry ae = AuctionsManager.getInstance().getEntry(auctionId);
    Currency snipeValue = Currency.getCurrency(ae.getCurBid().fullCurrencyName(), snipeAmount);

    ErrorManagement.logDebug("Remote-controlled snipe activated against auction " + auctionId + " for " + snipeValue);
    ae.prepareSnipe(snipeValue);

    JHTMLOutput jho = new JHTMLOutput("Activated snipe!",
                                      "Remote-controlled snipe activated on: " +
                                      auctionId + " for " + snipeValue +
                                      messageFinisher);
    return jho.getStringBuffer();
  }

  private StringBuffer cancelSnipe(String cancelCGI) {
    String auctionId = extractField(cancelCGI, findIDString);
    AuctionEntry ae = AuctionsManager.getInstance().getEntry(auctionId);

    if(ae != null) {
      ae.cancelSnipe(false);
      return new JHTMLOutput("Snipe canceled!", "Cancellation of snipe successful." +
                             messageFinisher).getStringBuffer();
    }
    return new JHTMLOutput("Could not find auction!",
                           "Cancellation of snipe failed, could not find auction " + auctionId + '!' +
                           messageFinisher).getStringBuffer();
  }

  protected StringBuffer checkError(StringBuffer result) {
    if(result != null) return result;

    return new JHTMLOutput("Invalid request", "Failed to correctly perform server-side actions." +
                           messageFinisher).getStringBuffer();
  }

  private static final StringBuffer noSyndication = new StringBuffer("<error>No syndication at this address.</error>");

  protected StringBuffer buildHTML(String whatDocument) {
    String relativeDocument = whatDocument;

    if(relativeDocument.charAt(0) == '/') {
      relativeDocument = relativeDocument.substring(1);
    }

    if(relativeDocument.endsWith(".jpg")) return null;

    if(relativeDocument.startsWith(syndicate)) {
      if(relativeDocument.indexOf(".xml") == -1) {
        return noSyndication;
      }
      return syndicate(relativeDocument.substring(syndicate.length(), relativeDocument.indexOf(".xml")));
    }
    boolean getCached = false;

    if(relativeDocument.startsWith("cached_")) {
      relativeDocument = relativeDocument.substring(7);
      getCached = true;
    }
    if(relativeDocument.startsWith(snipeCommand)) {
      return(checkError(setupSnipePage(relativeDocument.substring(snipeCommand.length()))));
    }


    if(relativeDocument.startsWith(addAuctionCommand)) {
      //Add new Auction
      addAuction(relativeDocument.substring(addAuctionCommand.length()));
      //show Overview
      HTMLDump htmlOutput = new HTMLDump();
      return(checkError(htmlOutput.createFullTable()));
    }

    if(relativeDocument.startsWith(activateSnipe)) {
      return(checkError(doSnipe(relativeDocument)));
    }

    if(relativeDocument.startsWith(cancelSnipe)) {
      return(cancelSnipe(relativeDocument));
    }

    if(relativeDocument.startsWith(event)) {
      return(fireEvent(relativeDocument));
    }

    if(relativeDocument.equalsIgnoreCase("jbidwatcher")) {
      AuctionsManager.getInstance().saveAuctions();
      return checkError(JTransformer.outputHTML(JConfig.queryConfiguration("savefile", "auctions.xml")));
    }

    if(relativeDocument.equals("synchronize")) {
      StringBuffer wholeData = new StringBuffer(25000);

      wholeData.append("<?xml version=\"1.0\"?>\n\n");
      wholeData.append(Constants.XML_SAVE_DOCTYPE);
      AuctionServerManager.getInstance().toXML().toStringBuffer(wholeData);

      return wholeData;
    }

    AuctionEntry ae = AuctionsManager.getInstance().getEntry(relativeDocument);

    if(ae == null) {
      return(new JHTMLOutput("Error!", "Error: No such auction in list!" +
                                       messageFinisher).getStringBuffer());
    }

    StringBuffer sbOut;

    if(getCached) {
      sbOut = new StringBuffer("<HTML><BODY><B>This is <a href=\"http://www.jbidwatcher.com\">JBidWatcher</a>'s cached copy.</B><br>");
      sbOut.append("Click here for the <a href=\"").append(ae.getServer().getBrowsableURLFromItem(ae.getIdentifier())).append("\">current page</a>.<hr>");
      sbOut.append(ae.getContent());
    } else {
      sbOut = new StringBuffer("<HTML><BODY><B>JBidWatcher View</B><br>");
      sbOut.append("Click here for the <a href=\"").append(ae.getServer().getBrowsableURLFromItem(ae.getIdentifier())).append("\">current page</a>.<br>");

      if(_item_list != null &&
         _item_list.size() > 1) {
        relativeDocument = ae.getIdentifier();

        int cur_item = _item_list.indexOf(relativeDocument);

        if(cur_item != -1) {
          if(cur_item != 0) {
            sbOut.append(getHTMLForEntry("Prev", _item_list.get(cur_item-1)));
            sbOut.append("&nbsp;");
          }
          for(int i=0; i<_item_list.size(); i++) {
            String showString;
            if(i==0) showString = "First";
            else if(i == (_item_list.size()-1)) showString = "Last";
            else showString = Integer.toString(i+1);

            if (i == cur_item) {
              sbOut.append('(').append(showString).append(')');
            } else {
              sbOut.append(getHTMLForEntry(showString, _item_list.get(i)));
            }
            sbOut.append("&nbsp;");
          }
          if(cur_item != (_item_list.size()-1)) {
            sbOut.append(getHTMLForEntry("Next", _item_list.get(cur_item+1)));
          }
        }
      }

      sbOut.append("<hr><br>");
      AuctionServer aucServ = ae.getServer();
      try {
        boolean got = false;
        if(JConfig.queryConfiguration("server.browseAffiliate", "true").equals("true")) {
          try {
            StringBuffer sb = aucServ.getAuctionViaAffiliate(aucServ.getNecessaryCookie(false), ae, ae.getIdentifier());
            if(sb != null) {
              sbOut.append(sb);
              got = true;
            }
          } catch (CookieJar.CookieException ignored) {
            //  Ignore it, because 'got' will be false, and the right thing will happen.
          }
        }
        if(!got) {
          sbOut.append(checkError(aucServ.getAuction(AuctionServer.getURLFromString(aucServ.getBrowsableURLFromItem(ae.getIdentifier())))));
        }
      } catch (FileNotFoundException ignored) {
        sbOut.append("<b><i>Item no longer appears on the server.</i></b><br>\n");
      }
    }

    return sbOut;
  }

  private StringBuffer fireEvent(String relativeDocument) {
    String eventName = extractField(relativeDocument, "name");
    String eventParam= extractField(relativeDocument, "param");

    if(eventName == null || eventParam == null) {
      return new JHTMLOutput("Invalid event", "No such event available." + messageFinisher).getStringBuffer();
    }
    eventParam = eventParam.replaceAll("\\+", " ").replaceAll("%20", " ");
    ErrorManagement.logMessage("Firing event to queue '" + eventName + "' with parameter '" + eventParam + "'");
    MQFactory.getConcrete(eventName).enqueue(eventParam);
    return new JHTMLOutput("Event posted", "Event has been submitted." + messageFinisher).getStringBuffer();
  }

  private StringBuffer genItems(String s) {
    StringBuffer sb = new StringBuffer(1500);
    Iterator<AuctionEntry> aucIterate = AuctionsManager.getAuctionIterator();
    ArrayList<AuctionEntry> allEnded = new ArrayList<AuctionEntry>();
    boolean checkEnded = false;

    if(s.equals("ended")) checkEnded = true;
    boolean checkEnding = false;
    if(s.equals("ending")) checkEnding = true;
    boolean checkBid = false;
    if(s.equals("bid")) checkBid = true;

    boolean done = false;
    int count = 0;
    while(!done && aucIterate.hasNext()) {
      AuctionEntry addme = aucIterate.next();
      if(checkEnded)
        if(addme.isEnded())
          allEnded.add(addme);
        else
          done = true;

      if(checkEnding && !addme.isEnded()) {
        count++;
        allEnded.add(addme);
        if(count >= Constants.SYNDICATION_ITEM_COUNT) done = true;
      }
      if(checkBid && (addme.isBidOn() || addme.isSniped())) {
        count++;
        allEnded.add(addme);
        if(count >= Constants.SYNDICATION_ITEM_COUNT) done = true;
      }
    }

    int lastEntry = Math.max(0, allEnded.size()-Constants.SYNDICATION_ITEM_COUNT);
    for(int i=allEnded.size()-1; i>=lastEntry; i--) {
      AuctionEntry ae = allEnded.get(i);
      sb.append("<item>\n");
      sb.append("<title><![CDATA[");
      sb.append(JBidMouse.stripHigh(ae.getTitle()));
      sb.append("]]></title>\n");

      sb.append("<link><![CDATA[");
      sb.append(ae.getServer().getBrowsableURLFromItem(ae.getIdentifier()));
      sb.append("]]></link>\n");

      DateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z");

      sb.append("<pubDate>");
      sb.append(df.format(ae.getEndDate()));
      sb.append("</pubDate>");

      sb.append("<description><![CDATA[");
      sb.append(JBidMouse.buildInfoHTML(ae, false, true));
      sb.append("]]></description>\n</item>\n");
    }

    return sb;
  }

  Map<String, String> labelToDescription;

  {
    labelToDescription = new HashMap<String, String>();
    labelToDescription.put("ended", "List of items ended recently.");
    labelToDescription.put("ending", "List of items ending soon.");
    labelToDescription.put("bid", "List of items being bid/sniped on.");
  }

  private StringBuffer syndicate(String s) {
    return new StringBuffer(15000).
            append("<?xml version=\"1.0\" ?>\n").
            append("<rss version=\"0.91\">\n").
            append("  <channel>\n").
            append("    <title>JBidwatcher Auctions</title>\n").
            append("    <link>/syndicate/").append(s).append(".xml</link>\n").
            append("    <description>").append(labelToDescription.get(s)).append("</description>").
            append("    <language>en-us</language>").
            append(genItems(s)).
            append("  </channel>\n").
            append("</rss>\n");
  }

  public static void setItems(Vector<String> v) {
    _item_list = v;
  }

  private String getHTMLForEntry(String linktext, String item_id) {
    AuctionEntry aePrev = AuctionsManager.getInstance().getEntry(item_id);

    if(aePrev.isInvalid()) {
      return "<a href=\"./cached_" + item_id + "\">" + linktext + "</a>";
    } else {
      return "<a href=\"./"        + item_id + "\">" + linktext + "</a>";
    }
  }
}
