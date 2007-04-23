// Copyright 2005 Google Inc. All Rights Reserved

package net.q00p.bots.partybot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.q00p.bots.Message;

/**
 * Simple plusplusbot implementation.
 *
 * Created on Mar 25, 2006
 * @author <a href="mailto:dolapo@gmail.com">Dolapo Falola</a>
 */
public class PlusPlusBot {
  
  private static final String LOG_DELIMITER = "\t";
  
  private static final String REASON_FORMAT = 
      " (%s)";

  private static final String INC_MESSAGE_FORMAT =
    "woot! %s -> %d%s";
  
  private static final String DEC_MESSAGE_FORMAT =
    "ouch! %s -> %d%s";

  private static final String OP_DESCRIPTION_FORMAT =
    "  %s by %s%s";
  
  /**
   * Things that can't be ++d
   */
  private final Set<String> blacklistedTargets;
  
  /**
   * chat -> {targetscore}
   */
  private final Map<String, Map<String, Score>> scoreBoard;
  
  private static class Score implements Comparable<Score> {
    private final String target;
    private final List<Op> ops = new LinkedList<Op>();
    private int score = 0;
    
    public Score(String target) {
      this.target = target;
    }
    
    public Message increment(String from, String reason) {
      return processOp(from, reason, true);
    }
    
    public Message decrement(String from, String reason) {
      return processOp(from, reason, false);
    }
    
    public String getTarget() {
      return target;
    }
    
    public int getCurrentScore() {
      return score;
    }
    
    public int compareTo(Score other) {
      return score - other.score;
    }
    
    private Message processOp(String from, String reason, boolean isIncrement) {
      score += isIncrement ? 1 : -1;
      
      Op op = new Op(this, from, reason, isIncrement);
      ops.add(op);
      
      return op.getResponseMessage(); 
    }
    
    private List<Op> getOps() {
      return Collections.unmodifiableList(ops);
    }
  }
  
  private static class Op {
    private final Score parent;
    private final String from;
    private final String reason;
    private final boolean isIncrement;
    
    public Op(Score parent, String from, String reason, boolean isIncrement) {
      this.parent = parent;
      this.from = from;
      this.reason = reason == null || reason.trim().length() == 0 
                    ? null
                    : reason;
      this.isIncrement = isIncrement;
    }
    
    private Message getResponseMessage() {
      String format = isIncrement ? INC_MESSAGE_FORMAT : DEC_MESSAGE_FORMAT;
      String formattedReason = getFormattedReason();
      String result = String.format(format, 
                                    parent.getTarget(),
                                    parent.getCurrentScore(),
                                    formattedReason);
      
      return new Message(null, null, result);
    }
    
    private String getFormattedReason() {
      return reason != null 
             ? String.format(REASON_FORMAT, reason)
             : "";    
    }
    
    private String getDescription() {
      String type = isIncrement ? "increment" : "decrement";
      String formattedReason = getFormattedReason();    
      return String.format(OP_DESCRIPTION_FORMAT, type, from, formattedReason);
    }
  }

  private BufferedWriter logFile;
  
  public PlusPlusBot() {
    blacklistedTargets = new HashSet<String>();
    scoreBoard = new HashMap<String, Map<String, Score>>();
    try {
      logFile = prepareAndLoadLog("ppblog");
    } catch (IOException e) {
      System.err.println("Unable to log ppb!");
    }
  }

  
  /**
   * We're too lazy to write proper tests. Use this instead. ;-)
   */
  public static void main(String[] args) {
    Matcher matcher = 
        Pattern.compile("(\\S+)(\\+\\+|\\-\\-)\\W*(\\w*.*)").matcher("cows++");
    matcher.find();
    System.err.println(matcher.group(1));
    PlusPlusBot ppb = new PlusPlusBot();
    System.out.println(ppb.getScores("chat", null, false));
    ppb.doCommand("sender", "chat", "target", "reason", true);
    System.out.println(ppb.getScores("chat", null, false));
    try {
      ppb.logFile.flush();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
  
  /**
   * For now, assume partychat is so small that we can load a full log
   * at startup.
   * @throws IOException 
   */
  private BufferedWriter prepareAndLoadLog(String filename) throws IOException {
    File file = new File(filename);
    // Replay any existing log
    if (file.exists()) {
      BufferedReader rdr = new BufferedReader(new FileReader(file));
      String line;
      while (null != (line = rdr.readLine())) {
        String[] logElts = line.split(LOG_DELIMITER);
        if (logElts.length != 5) {
          continue; // skip things we don't understand
        }
        doCommand(logElts[0], logElts[1], logElts[2], logElts[3],
            logElts[4].equals("+"));
      }
      rdr.close(); // do we also need to close the file reader?
    } else {
      file.createNewFile();
    }
    return new BufferedWriter(new FileWriter(file, true));
  }


  public Message increment(
      Message message, String chat, String target, String reason) {
    return doCommand(message.getFrom().getName(), chat, target, reason, true);
  }

  public Message decrement(
      Message message, String chat, String target, String reason) {
    return doCommand(message.getFrom().getName(), chat, target, reason, false);
  }


  public Message doCommand(String from, String chat, String target,
      String reason, boolean increment) {
    
    // Skip the blacklist
    if (blacklistedTargets.contains(target)) {
      return null;
    }
    
    // Log the event
    try {
      logEvent(from, chat, target, reason, increment);
    } catch (IOException e) {
      e.printStackTrace();
    }
    
    // Update the map and generate a response message
    return updateScore(from, chat, target, reason, increment);
  }

  private void logEvent(String from, String chat, String target, String reason,
      boolean increment) throws IOException {
    if (logFile == null) {
      return;
    }
    reason = reason.replaceAll("\n", " ").replaceAll("\t", " "); // just in case
    for (String s : new String[] {from, chat, target, reason}) {
      logFile.write(s);
      logFile.write(LOG_DELIMITER);
    }
    logFile.write(increment ? "+" : "-");
    logFile.newLine();
    logFile.flush();
  }

  private Message updateScore(String from, String chat, String target, 
      String reason, boolean increment) {
    Map<String, Score> scores = getScoreBoard(chat);

    Score score = scores.get(target);
    if (score == null) {
      score = new Score(target);
      scores.put(target, score);
    }
    
    if (increment) {
      return score.increment(from, reason);
    } else {
      return score.decrement(from, reason);
    }
  }
  
  private synchronized Map<String, Score> getScoreBoard(String chat) {
    if (scoreBoard.containsKey(chat)) {
      return scoreBoard.get(chat);
    }
    Map<String, Score> scores = Collections.synchronizedMap(
        new HashMap<String, Score>());
    scoreBoard.put(chat, scores);
    return scores;
  }
  
  public String getScores(String chat, String regex, boolean showReasons) {
    List<Score> scoresToShow = new ArrayList<Score>();
    Collection<Score> allScores = getScoreBoard(chat).values();
    
    if (regex != null && !regex.equals("")) {
      Pattern searchPattern;
      try {
        searchPattern = Pattern.compile(regex);
      } catch (PatternSyntaxException e) {
        return "invalid pattern";
      }
      
      for (Score score : allScores) {
        if (searchPattern.matcher(score.getTarget()).matches()) {
          scoresToShow.add(score);
        }
      }  
    } else {
      scoresToShow.addAll(allScores);
    }
    
    // Sort entries by score value
    Collections.sort(scoresToShow);    
    
    // Render response
    StringBuilder result = new StringBuilder();
    for (Score score : scoresToShow) {
      if (result.length() > 0) {
        result.append("\n");
      }
      result.append(score.getTarget())
            .append(":")
            .append(score.getCurrentScore());

      if (showReasons) {
        for (Op op : score.getOps()) {
          result.append("\n");
          result.append(op.getDescription());
        }
      }
    }
    
    if (result.length() == 0) {
      return "no scores found";
    }
    
    return result.toString();
  }
}
