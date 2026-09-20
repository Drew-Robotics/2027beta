// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Seconds;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.wpilib.datalog.DataLogReader;
import org.wpilib.datalog.DataLogRecord;
import org.wpilib.hardware.hal.AllianceStationID;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.OpModeOption;
import org.wpilib.simulation.DriverStationSim;
import org.wpilib.simulation.SimHooks;
import org.wpilib.system.DataLogManager;
import org.wpilib.system.RobotController;
import org.wpilib.units.measure.Time;

// Tier 2, like WiringTest: the real Robot, and assertions about wiring rather than about physics.
// ZoneEntry is only correct if the trigger behind it fired on a crossing and nothing else, so what
// is checked is when the writes land relative to the enable and the disable — one bucket per
// firing the bug produced, so a red test names which one came back rather than only a total.
@ResourceLock("timing")
class ZoneEntryWiringTest {
  private static final String OPMODE = "SweepLeftAuto";

  // The backend Robot registers at the root prefix writes under /Telemetry, so the entry name in
  // the file is not the table name the opmode logs to.
  private static final String ENTRY = "/Telemetry/Auto/ZoneEntry";

  private static final String LOG_FILE = "zone-entry.wpilog";

  // Comfortably longer than the second or so the path takes to reach the line, so the window is
  // not itself the thing under test.
  private static final Time AUTO = Seconds.of(6);

  // The disable is the scenario, not the teardown: it closes the opmode and builds a fresh one
  // around a selection that stayed put, with the robot sitting wherever the path left it.
  private static final Time REBUILD = Seconds.of(1.5);

  private static final Time SHUTDOWN = Seconds.of(5);

  @TempDir static Path logDir;

  @BeforeEach
  void setUp() {
    // Before any SimHooks call: the timing hooks lock a mutex the HAL creates, so a JVM that has
    // not initialised it segfaults here rather than throwing.
    HAL.initialize();
    SimHooks.pauseTiming();
    SimHooks.setProgramStarted(false);
    DriverStationSim.resetData();

    // Started before the Robot, which would otherwise pick the manager's default directory and
    // leave the file somewhere this test cannot name. getLog() adopts a manager already running.
    DataLogManager.start(logDir.toString(), LOG_FILE);
  }

  @AfterEach
  void tearDown() {
    SimHooks.resumeTiming();
  }

  @Test
  void aSweepWritesOneZoneEntryAcrossSelectEnableAndDisable() throws IOException {
    var failure = new AtomicReference<Throwable>();
    var robot = new Robot();
    var thread = new Thread(robot::startCompetition);
    thread.setUncaughtExceptionHandler((t, e) -> failure.set(e));

    long enabledAt;
    long disabledAt;
    try {
      thread.start();
      SimHooks.waitForProgramStart();

      DriverStationSim.setDsAttached(true);
      DriverStationSim.setAllianceStationId(AllianceStationID.BLUE_1);
      DriverStationSim.notifyNewData();

      // Selected first and enabled second, which is the order the operator does it in: the opmode
      // is constructed on selection, and its enabled-trigger needs an edge to fire on. The
      // constructor runs here, with the pose still at its default and already past the line in
      // the authored frame.
      select();

      // Both clocks are wpi::Now in nanoseconds — RobotController.getTime() here and
      // DataLogRecord.getTimestamp() on the way back out — so the writes bucket against these.
      enabledAt = RobotController.getTime();
      enable(AUTO);
      disabledAt = RobotController.getTime();
      disable(REBUILD);
    } finally {
      robot.endCompetition();
      joinQuietly(thread);
      robot.clearOpModes();
      robot.close();
    }

    DataLogManager.getLog().flush();
    var writes = zoneEntryTimestamps();

    assertNull(failure.get());
    // All three together: the bug wrote a spurious entry either side of the real one, and one
    // assertion at a time would report only the first of them.
    assertAll(
        () ->
            assertEquals(
                0,
                writes.stream().filter(at -> at < enabledAt).count(),
                "a zone entry was logged before the enable, at whatever pose it was built with"),
        () ->
            assertEquals(
                1,
                writes.stream().filter(at -> at >= enabledAt && at < disabledAt).count(),
                "autonomous logged no single zone entry; 0 means the path never reached the line"),
        () ->
            assertEquals(
                0,
                writes.stream().filter(at -> at >= disabledAt).count(),
                "a zone entry was logged after the disable, where the rebuild is standing still"));
  }

  // The timestamp of every write to ZoneEntry, which is all this test reads: the pose under it is
  // Tier 1's to have an opinion about.
  private static List<Long> zoneEntryTimestamps() throws IOException {
    var reader = new DataLogReader(logDir.resolve(LOG_FILE).toString());
    assertTrue(reader.isValid(), "the run did not write a readable WPILOG");

    Set<Integer> entries = new HashSet<>();
    var writes = new ArrayList<Long>();
    for (DataLogRecord record : reader) {
      if (record.isStart()) {
        var start = record.getStartData();
        if (ENTRY.equals(start.name)) {
          entries.add(start.entry);
        }
      } else if (!record.isControl() && entries.contains(record.getEntry())) {
        writes.add(record.getTimestamp());
      }
    }
    return writes;
  }

  private static void select() {
    var options = DriverStationSim.getOpModeOptions();
    var option = Arrays.stream(options).filter(o -> OPMODE.equals(o.name)).findFirst().orElse(null);
    assertNotNull(option, "no opmode named " + OPMODE + " in " + names(options));

    // The mode and the id are separate fields on the simulated Driver Station and the control word
    // is assembled from both, so setting only the id leaves the lookup missing silently.
    DriverStationSim.setRobotMode(option.getMode());
    DriverStationSim.setOpMode(option.id);
    DriverStationSim.notifyNewData();
    step(Constants.LOOP_PERIOD);
  }

  private static void enable(Time hold) {
    DriverStationSim.setEnabled(true);
    DriverStationSim.notifyNewData();
    step(hold);
  }

  private static void disable(Time hold) {
    DriverStationSim.setEnabled(false);
    DriverStationSim.notifyNewData();
    step(hold);
  }

  private static void step(Time slice) {
    SimHooks.stepTiming(slice.in(Seconds));
  }

  private static void joinQuietly(Thread thread) {
    try {
      thread.join((long) SHUTDOWN.in(Milliseconds));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static List<String> names(OpModeOption[] options) {
    return Arrays.stream(options).map(o -> o.name).toList();
  }
}
