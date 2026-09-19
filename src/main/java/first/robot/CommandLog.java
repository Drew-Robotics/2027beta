// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Nanoseconds;
import static org.wpilib.units.Units.Seconds;

import java.util.ArrayList;
import java.util.List;
import org.wpilib.command3.Command;
import org.wpilib.command3.Scheduler;
import org.wpilib.command3.SchedulerEvent;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.util.Alert;
import org.wpilib.util.Alert.Level;

/**
 * The command timeline: {@code /Commands/Scheduler}, the proto snapshot of what is running, and
 * {@code /Commands/Events}, the transitions between one snapshot and the next. The snapshot cannot
 * see a command that never yields, which is why both exist.
 */
public final class CommandLog implements AutoCloseable {
  private final TelemetryTable commandsLog;
  private final TelemetryTable eventsLog;
  private final Scheduler scheduler;

  // Filled by the listener as the scheduler runs and drained once per loop, so every event in one
  // batch shares a write timestamp and the events' own stamps order them within it.
  private final List<Event> batch = new ArrayList<>();

  // CompletedWithError is the only place a command's exception surfaces, so it latches an alert as
  // well as a log line.
  private final Alert commandFailed = new Alert("command-failed", "A command threw", Level.HIGH);

  private record Event(String type, String command, long timestampNanos, String detail) {}

  public CommandLog(TelemetryTable commandsLog, Scheduler scheduler) {
    this.commandsLog = commandsLog;
    this.scheduler = scheduler;
    eventsLog = commandsLog.getTable("Events");

    // An idle tree serialises to the same bytes every loop, and the backend writes a sample only
    // when they change. Without this, "the same commands ran for the whole match" and "logging
    // stopped" are one stretch of silence. It costs nothing on the robot, where lastTimeMs jitters
    // and every message is already unique; it is simulation's stepped clock that makes them equal.
    commandsLog.keepDuplicates("Scheduler");

    // Repeated command names are the normal case, and suppression would swallow a command that was
    // scheduled twice in the same shape.
    eventsLog.keepDuplicates("Types");
    eventsLog.keepDuplicates("Commands");
    eventsLog.keepDuplicates("Timestamps");
    eventsLog.keepDuplicates("Details");
    // A property value has to be JSON, so the symbol is quoted inside the string.
    eventsLog.setProperty("Timestamps", "unit", "\"s\"");

    scheduler.addEventListener(this::record);
  }

  public void log() {
    commandsLog.log("Scheduler", scheduler, Scheduler.proto);

    // Most loops have nothing to say, and with duplicates kept an empty batch written every loop
    // would be 200 samples a second of no events.
    if (batch.isEmpty()) {
      return;
    }

    eventsLog.log("Types", batch.stream().map(Event::type).toArray(String[]::new));
    eventsLog.log("Commands", batch.stream().map(Event::command).toArray(String[]::new));
    eventsLog.log(
        "Timestamps",
        batch.stream().mapToDouble(e -> Nanoseconds.of(e.timestampNanos()).in(Seconds)).toArray());
    eventsLog.log("Details", batch.stream().map(Event::detail).toArray(String[]::new));
    batch.clear();
  }

  @Override
  public void close() {
    commandFailed.close();
  }

  private void record(SchedulerEvent schedulerEvent) {
    var event = transition(schedulerEvent);
    if (event == null) {
      return;
    }
    batch.add(event);

    // Raised here rather than on the flush: alpha-7's scheduler rethrows the command's exception
    // out of run(), so this is the last line that runs before the failure leaves the loop.
    if (schedulerEvent instanceof SchedulerEvent.CompletedWithError e) {
      commandFailed.setText(e.command().name() + " threw " + e.error());
      commandFailed.set(true);
    }
  }

  private static Event transition(SchedulerEvent event) {
    return switch (event) {
      case SchedulerEvent.Scheduled e -> event("Scheduled", e.command(), e.timestampNanos(), "");
      case SchedulerEvent.Completed e -> event("Completed", e.command(), e.timestampNanos(), "");
      case SchedulerEvent.Canceled e -> event("Canceled", e.command(), e.timestampNanos(), "");
      case SchedulerEvent.Interrupted e ->
          event("Interrupted", e.command(), e.timestampNanos(), e.interrupter().name());
      case SchedulerEvent.CompletedWithError e ->
          event("CompletedWithError", e.command(), e.timestampNanos(), String.valueOf(e.error()));
      // A running command mounts and yields once per loop, which at 5 ms buries the transitions
      // this timeline is for. That steady state is what /Commands/Scheduler already carries.
      case SchedulerEvent.Mounted _, SchedulerEvent.Yielded _ -> null;
    };
  }

  private static Event event(String type, Command command, long timestampNanos, String detail) {
    return new Event(type, command.name(), timestampNanos, detail);
  }
}
