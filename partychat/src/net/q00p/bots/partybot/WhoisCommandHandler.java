package net.q00p.bots.partybot;

import java.util.regex.Matcher;

public class WhoisCommandHandler extends PartyLineCommandHandler {

  public String doCommand(PartyBot partyBot, PartyLine partyLine,
      Subscriber subscriber, Matcher commandMatcher) {
    // Find a subscriber with that alias or "name".
    String alias = commandMatcher.group(2);
    Subscriber sub = partyBot.findSubscriber(partyLine, alias);
    if (sub != null) {
      return formatSubscriberInfo(sub);
    }
    // Sorry, no such subscriber.
    return String.format(PartyBot.NO_SUBSCRIBER, alias);
  }

  /**
   * Returns a message like: joe@gmail.com (joe) joined chat @ [time] last seen @ [time]
   * total messages [num] total words [num] snoozing for [time]
   * 
   */
  private String formatSubscriberInfo(Subscriber subscriber) {
    StringBuffer sb = new StringBuffer();
    sb.append(subscriber.getUser().getName());
    if (subscriber.getAlias() != null) {
      sb.append(" (").append(subscriber.getAlias()).append(")");
    }

    if (subscriber.getLineJoinTime() > 0) {
      sb.append("\nMember for ").append(
          PartyBot.timeSince(subscriber.getLineJoinTime()));
    }

    if (subscriber.getLastActivityTime() > 0) {
      sb.append("\nLast seen ").append(
          PartyBot.timeSince(subscriber.getLastActivityTime())).append(" ago");
    }

    if (subscriber.getHistory().getTotalMessageCount() > 0) {
      sb.append("\nTotal messages: ").append(
          subscriber.getHistory().getTotalMessageCount());
    }

    if (subscriber.getHistory().getTotalWordCount() > 0) {
      sb.append("\nApproximate words: ").append(
          subscriber.getHistory().getTotalWordCount());
    }

    if (subscriber.isSnoozing()) {
      sb.append("\nSnoozing for "
          + PartyBot.timeTill(subscriber.getSnoozeUntil()));
    }
    return sb.toString();
  }

}