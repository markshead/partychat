package net.q00p.bots.partybot;

import net.q00p.bots.Bot;
import net.q00p.bots.Message;
import net.q00p.bots.User;
import net.q00p.bots.io.Logger;
import net.q00p.bots.partybot.marshal.PartyLineBean;
import net.q00p.bots.partybot.marshal.SubscriberBean;
import net.q00p.bots.util.AbstractBot;
import net.q00p.bots.util.DateUtil;
import net.q00p.bots.util.FutureTask;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;


public class PartyBot extends AbstractBot implements MessageResponder {
  private final LineManager lineManager;
  private final Timer timer;
  private final StateSaver sh;
  private final PlusPlusBot plusPlusBot;

  static final String NO_SUBSCRIBER = "No such alias or name: %s";
  static final String MESSAGE_FORMAT = "[%s] %s";
  static final String IGNORE_MESSAGE_FORMAT = "[%s to all but %s] %s";

  static final String UKNOWN_COMMAND = "'%s' is not a recognized "
      + "command, type '" + Command.COMMANDS.getShortName()
      + "' for a list of possible commands " + "and their uses.";
  static final String SUB_STATUS_ONLINE = "you are currently in party chat #%s"
      + " as %s";
  static final String SUB_STATUS_OFFLINE = "you are not in a party chat";

  // TODO(ak): load administrators from a config file  
  private static final Set<String> ADMINISTRATORS = ImmutableSet.of(
    "apatil@gmail.com",
    "mbolin@gmail.com",
    "ak@q00p.net",
    "mihai.parparita@gmail.com"
  );

  private final FutureTask futureTask = new FutureTask();
  
  private final List<? extends MessageHandler> messageHandlers;

  private PartyBot(String name) {
    super(name);
    lineManager = loadState();

    plusPlusBot = new PlusPlusBot();
    
    messageHandlers = ImmutableList.of(
        new PlusPlusBot.MessageHandler(plusPlusBot),
        new SearchReplaceMessageHandler(),
        new BroadcastMessageHandler()
    );
    sh = new StateSaver(lineManager);
    Runtime.getRuntime().addShutdownHook(new Thread(sh));

    timer = new Timer();
    timer.scheduleAtFixedRate(sh, new Date(), 15 * 60 * 1000); // every 15
    // minutes.
  }

  @Override
  public void handleMessage(Message message) {
    Subscriber subscriber = 
        Subscriber.get(message.getFrom(), message.getTo().getName());

    String content = message.getPlainContent();

    Command command = Command.isCommand(content);
    if (command != null) {
      CommandHandler commandHandler = command.handler;
      Matcher commandMatcher = command.pattern.matcher(content);
      // need to run matches() before CommandHandler can access groups()
      boolean doesMatch = commandMatcher.matches();
      assert doesMatch;
      String commandOutput = commandHandler.doCommand(
          this, lineManager, subscriber, commandMatcher);
      if (commandOutput != null) {
        reply(message, commandOutput);
      }
      return;
    }

    PartyLine partyLine = lineManager.getPartyLine(subscriber);
    
    for (MessageHandler messageHandler : messageHandlers) {
      if (messageHandler.canHandle(message)) {
        if (messageHandler.shouldBroadcastOriginalMessage()) {
          broadcast(subscriber, partyLine, message);          
        }
        messageHandler.handle(message, subscriber, partyLine, this);
        break;
      }
    }
  }  

  FutureTask getFutureTask() {
    return futureTask;
  }

  /**
   * Finds a subscriber with the given alias or name.
   * 
   * @param partyLine
   * @param aliasOrName
   * @return The Subscriber or null.
   */
  Subscriber findSubscriber(PartyLine partyLine, String aliasOrName) {
    for (Subscriber sub : partyLine.getSubscribers()) {
      if (aliasOrName.equals(sub.getAlias())
          || aliasOrName.equals(sub.getUser().getName())) {
        return sub;
      }
    }

    return null;
  }

  /**
   * subscriber - can be null.
   */
  void broadcast(Subscriber subscriber, PartyLine partyLine, String content,
      boolean isSystem) {
    if (!isSystem)
      content = String.format(MESSAGE_FORMAT, subscriber.getDisplayName(),
          content);

    for (Subscriber listener : partyLine.getSubscribers()) {
      if (!listener.equals(subscriber)) {
        if (listener.isSnoozing()) {
          // TODO(dolapo) - Actually save the messages. Post sqlite thing.
          // listener.addDelayedMessage(content);
          continue;
        }
        Message msg = new Message(User.get(listener.getBotScreenName(),
            botName()), listener.getUser(), content);
        getMessageSender().sendMessage(msg);
      }
    }
  }

  public void broadcast(
      Subscriber subscriber, PartyLine partyLine, Message message) {
    broadcast(subscriber, partyLine, message.getContent(), false);
  }


  public void reply(Message inReplyTo, String message) {
    getMessageSender().sendMessage(inReplyTo.reply(message));
  }

  public void announce(PartyLine partyLine, String message) {
    // Do nothing if there's somehow no partyline
    if (partyLine != null) {
      broadcast(null, partyLine, message, true);
    }
  }

  String getStatus(Subscriber sub) {
    PartyLine partyLine = lineManager.getPartyLine(sub);
    if (partyLine == null) {
      return SUB_STATUS_OFFLINE;
    } else {
      return String.format(SUB_STATUS_ONLINE, partyLine.getName(), sub
          .getDisplayName());
    }
  }

  /** Prints how long till the given time */
  static String timeTill(long futureTimeMs) {
    return DateUtil.prettyFormatTime(futureTimeMs - System.currentTimeMillis());
  }

  static String timeSince(long pastTimeMs) {
    return DateUtil.prettyFormatTime(System.currentTimeMillis() - pastTimeMs);
  }

  String printScore(String chat, String regex, boolean showReasons) {
    return plusPlusBot.getScores(chat, regex, showReasons);
  }

  String saveState(Subscriber subscriber) {
    String user = subscriber.getUser().getName();
    if (ADMINISTRATORS.contains(user)) {
      sh.run();
      return "state saved";
    } else {
      return "authorized personnel only";
    }
  }

  public static void main(String[] args) {
    List<String> argList = Arrays.asList(args);
    assert argList.size() > 2 : "usage: java PartyBot botName usn pwd usn pwd ...";
    Bot bot = new PartyBot(argList.get(0));
    Logger.log(String.format("Running %s with parameters: %s", bot.getClass(),
        Arrays.toString(args)), true);
    run(bot, argList.subList(1, argList.size()));
  }

  //TODO: This is lame. We should have some sort of backend
  class StateSaver extends TimerTask {
    LineManager manager;

    public StateSaver(LineManager managedClass) {
      super();
      this.manager = managedClass;
    }

    @Override
    public void run() {
      Logger.log("saving state...");
      String xmlfilename = "state.xml";
      try {
        Collection<PartyLine> blah = lineManager.linesByName.values();
        Collection<PartyLineBean> blah2 = new HashSet<PartyLineBean>();
        for (PartyLine pl : blah) {
          blah2.add(new PartyLineBean(pl));
        }
        XMLEncoder e = new XMLEncoder(new BufferedOutputStream(
            new FileOutputStream(xmlfilename)));
        e.writeObject(blah2);
        e.close();
        Logger.log("State successfully stored to " + xmlfilename);
      } catch (Exception e) {
        Logger.log("Error trying to save state: " + e.getMessage());
      }

    }
  }

  @SuppressWarnings("unchecked")
  private LineManager loadState() {
    Collection<PartyLineBean> result = null;
    try {
      XMLDecoder d = new XMLDecoder(new BufferedInputStream(
          new FileInputStream("state.xml")));
      result = (Collection<PartyLineBean>) d.readObject();
      d.close();
    } catch (FileNotFoundException e) {
      Logger.log("No preexisting state found to load.", true);
    }
    LineManager lm = new LineManager();
    if (result == null) return lm;
    for (PartyLineBean plb : result) {
      lm.startLine(plb.getName(), plb.getPassword());
      for (SubscriberBean sb : plb.getSubscribers()) {
        lm.subscribe(sb.loadSubscriber(), plb.getName(), plb.getPassword());
      }
    }
    return lm;
  }
}
