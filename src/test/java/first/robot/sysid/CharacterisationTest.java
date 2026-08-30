// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sysid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Microseconds;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Radians;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.RotationsPerSecond;
import static org.wpilib.units.Units.Seconds;
import static org.wpilib.units.Units.Volts;

import first.robot.Constants;
import first.robot.DriveConstants;
import first.robot.sim.OnboardLoopSim;
import first.robot.sim.SimModuleState;
import first.robot.sim.SwerveDriveSim;
import first.robot.sysid.SysIdRoutine.Direction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.wpilib.command3.Command;
import org.wpilib.command3.Mechanism;
import org.wpilib.command3.Scheduler;
import org.wpilib.datalog.DataLogReader;
import org.wpilib.datalog.DataLogRecord;
import org.wpilib.datalog.StringLogEntry;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.util.MathUtil;
import org.wpilib.simulation.SimHooks;
import org.wpilib.sysid.SysIdRoutineLog;
import org.wpilib.system.DataLogManager;
import org.wpilib.units.measure.Time;
import org.wpilib.units.measure.Voltage;

// The characterisation pipeline with no hardware in it: the three ported routines drive ADR 0010's
// plant through the SPARK-side steer loop, SysIdRoutineLog writes a WPILOG, and the log is read
// back through the rule tools/sysid discovers tests by. The callbacks here mirror Drive's, because
// no SPARK can be constructed in this JVM. What this proves is the pipeline; none of it is a
// number about a robot.
//
// One log, written once: DataLogManager is process-wide and its directory is fixed by the first
// start() anything makes, so every assertion below reads the same file.
//
// The clock is stepped rather than injected. DataLog stamps every record with wpi::Now, which the
// simulation HAL points at its own monotonic time (MockHooks.cpp: SetNowImpl(GetMonotonicTime)),
// and the analyser reads velocity and acceleration out of those timestamps. Left on the wall
// clock, a minute of simulated ramp lands inside a fraction of a second of real time and the file
// is unanalysable. Stepping it makes the log the analyser's, not just ours — see the sysidLog
// task, which writes this same file somewhere a human can pick it up.
@TestInstance(Lifecycle.PER_CLASS)
@ResourceLock("timing")
class CharacterisationTest {
  private static final int MODULES = 4;
  private static final Time STEP = Constants.LOOP_PERIOD;
  private static final double SUB_STEP = DriveConstants.CONTROLLER_PERIOD.in(Seconds);
  private static final int SUB_STEPS = (int) Math.round(STEP.in(Seconds) / SUB_STEP);
  private static final Time BUDGET = Seconds.of(600);
  private static final int SETTLE_CYCLES =
      (int) Math.round(DriveConstants.CHARACTERISATION_SETTLE.in(Seconds) / STEP.in(Seconds));
  private static final int RELEASE_CYCLES = 3;
  private static final int TESTS_PER_ROUTINE = 4;
  private static final int ROUTINES = 3;
  private static final Time WRITE_DEADLINE = Seconds.of(10);
  private static final long POLL = 150;
  private static final int STABLE_READS = 4;
  // Metres per second, or radians per second for the rotation routine. Loose: what it has to rule
  // out is a test opening at the several units per second the previous one left behind.
  private static final double AT_REST = 0.05;
  private static final String LOG_DIR_PROPERTY = "sysid.logDir";
  private static final String LOG_FILE = "sysid-simulation.wpilog";
  private static final String MOTOR = "FrontLeft";
  private static final String DRIVE_LOG = "drive";
  private static final String STEER_LOG = "steer";
  private static final String ROTATION_LOG = "rotation";
  private static final double WHEEL_RADIUS = DriveConstants.WHEEL_RADIUS.in(Meters);

  private final SwerveDriveKinematics kinematics =
      new SwerveDriveKinematics(DriveConstants.simConfig().moduleLocations());
  private final SwerveDriveSim physics = new SwerveDriveSim(DriveConstants.simConfig());
  private final OnboardLoopSim[] steerLoops = new OnboardLoopSim[MODULES];
  private final double[] driveVolts = new double[MODULES];
  private final double[] steerVolts = new double[MODULES];
  private final double[] steerSetpoints = new double[MODULES];
  private final Rotation2d[] spinAzimuths = new Rotation2d[MODULES];

  private boolean steerOpenLoop;
  private double lastDriveCommand;
  private double lastSteerCommand;
  private SimModuleState[] state;
  private double azimuthRate;
  private double yawRadians;
  private Rotation2d lastRotation = Rotation2d.kZero;
  private Time now = Seconds.zero();
  private Log log;

  @TempDir static Path logDir;

  @BeforeAll
  void runTheWholePipeline() throws IOException, InterruptedException {
    // The HAL first: SimHooks reaches straight into the simulation HAL's own timing state, and
    // touching it before that library is up segfaults the JVM rather than throwing.
    HAL.initialize();
    SimHooks.pauseTiming();
    SimHooks.restartTiming();

    var scheduler = Scheduler.createIndependentScheduler();
    var wheels = new Wheels(scheduler);
    var gains = DriveConstants.SIM_GAINS;
    state = physics.moduleStates();
    lastRotation = physics.truePose().getRotation();
    var spin = kinematics.toSwerveModuleVelocities(new ChassisVelocities(0, 0, 1));
    for (int i = 0; i < MODULES; i++) {
      steerLoops[i] =
          OnboardLoopSim.position(
              gains.steer().kP(), gains.steer().kD(), gains.steer().dFilter(), 0, 1);
      spinAzimuths[i] = spin[i].angle;
    }

    var drive =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                DriveConstants.DRIVE_RAMP_RATE,
                DriveConstants.DRIVE_STEP_VOLTAGE,
                DriveConstants.CHARACTERISATION_TIMEOUT),
            new SysIdRoutine.Mechanism(this::commandDrive, this::logDrive, wheels, DRIVE_LOG));
    var steer =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                DriveConstants.STEER_RAMP_RATE,
                DriveConstants.DRIVE_STEP_VOLTAGE,
                DriveConstants.CHARACTERISATION_TIMEOUT),
            new SysIdRoutine.Mechanism(this::commandSteer, this::logSteer, wheels, STEER_LOG));
    var rotation =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                DriveConstants.ROTATION_RAMP_RATE,
                DriveConstants.ROTATION_STEP_VOLTAGE,
                DriveConstants.CHARACTERISATION_TIMEOUT),
            new SysIdRoutine.Mechanism(
                this::commandRotation, this::logRotation, wheels, ROTATION_LOG));

    // start() first, and only then the NetworkTables flag: logNetworkTables() with no log yet
    // calls start() itself, which would put this log in the robot's default directory.
    var directory = logDirectory();
    var file = directory.resolve(LOG_FILE);
    // start() opens the file with create-new and quietly randomises the name if one is already
    // there, which would leave the assertions reading the previous run's log.
    Files.createDirectories(directory);
    Files.deleteIfExists(file);
    DataLogManager.start(directory.toString(), LOG_FILE);
    DataLogManager.logNetworkTables(false);
    assertEquals(
        directory.toString(),
        DataLogManager.getLogDir(),
        "something else in this JVM started the data log first, so this log is not ours");

    // The output callback travels with its routine, because the settle in front of each test has
    // to point the modules the way that routine's ramp will push.
    record Under(SysIdRoutine routine, Consumer<Voltage> output) {}
    for (var each :
        List.of(
            new Under(drive, this::commandDrive),
            new Under(steer, this::commandSteer),
            new Under(rotation, this::commandRotation))) {
      run(scheduler, each.routine().quasistatic(Direction.FORWARD), each.output());
      run(scheduler, each.routine().quasistatic(Direction.REVERSE), each.output());
      run(scheduler, each.routine().dynamic(Direction.FORWARD), each.output());
      run(scheduler, each.routine().dynamic(Direction.REVERSE), each.output());
    }

    // The record appended last is not visible to a reader of a log whose writer is still open, so
    // the last thing written is a marker nothing asserts on rather than the last routine's "none".
    new StringLogEntry(DataLogManager.getLog(), "end-of-run").append("");
    log = readWhenWritten(file);
    System.out.println("SysId log: " + file.toAbsolutePath());
  }

  // The temporary directory unless somebody asked for a real one. The sysidLog Gradle task points
  // this at logs/, which is where a run meant for the analyser rather than for an assertion goes.
  private static Path logDirectory() {
    var override = System.getProperty(LOG_DIR_PROPERTY, "");
    return override.isBlank() ? logDir : Path.of(override);
  }

  // flush() only wakes the writer thread; it does not block until the bytes are on disk, and
  // stop() beside it races the same thread into closing the file with records still buffered. So
  // the log is flushed, left running, and read once its size has stopped changing.
  private static Log readWhenWritten(Path file) throws IOException, InterruptedException {
    DataLogManager.getLog().flush();

    var deadline = Instant.now().plusMillis((long) WRITE_DEADLINE.in(Milliseconds));
    var log = Log.read(file);
    int stable = 0;
    while (stable < STABLE_READS && Instant.now().isBefore(deadline)) {
      int before = log.samples().size();
      Thread.sleep(POLL);
      log = Log.read(file);
      stable = log.samples().size() == before ? stable + 1 : 0;
    }
    return log;
  }

  @AfterAll
  void restoreClock() {
    SimHooks.resumeTiming();
  }

  // Four, in every routine. The analyser combines all four into one dataset before it fits
  // anything, so a routine that ran two of them writes a log it refuses to open.
  @Test
  void theAnalyserFindsFourTestsInEveryRoutinesLog() {
    for (var routine : List.of(DRIVE_LOG, STEER_LOG, ROTATION_LOG)) {
      assertEquals(
          Set.of(
              "quasistatic-forward", "quasistatic-reverse", "dynamic-forward", "dynamic-reverse"),
          log.tests(routine),
          "the analyser does not discover four tests in the " + routine + " routine's log");
    }
  }

  @Test
  void theThreeRoutinesEntriesDoNotInterleave() {
    assertEquals(
        Set.of(
            "voltage-" + MOTOR + "-" + DRIVE_LOG,
            "position-" + MOTOR + "-" + DRIVE_LOG,
            "velocity-" + MOTOR + "-" + DRIVE_LOG,
            "voltage-" + MOTOR + "-" + STEER_LOG,
            "position-" + MOTOR + "-" + STEER_LOG,
            "velocity-" + MOTOR + "-" + STEER_LOG,
            "voltage-" + MOTOR + "-" + ROTATION_LOG,
            "position-" + MOTOR + "-" + ROTATION_LOG,
            "velocity-" + MOTOR + "-" + ROTATION_LOG),
        log.motorEntries(),
        "the log does not carry exactly voltage, position and velocity for each of three routines");
  }

  @Test
  void everyRoutineMovedWhatItMeasures() {
    assertTrue(
        log.values("velocity-" + MOTOR + "-" + DRIVE_LOG).stream().anyMatch(v -> v > 0.1),
        "the wheel never turned, so the drive routine drove nothing");
    assertTrue(
        log.values("velocity-" + MOTOR + "-" + STEER_LOG).stream().anyMatch(v -> Math.abs(v) > 0),
        "the module never steered, so the steer routine drove nothing");
    assertTrue(
        log.values("velocity-" + MOTOR + "-" + ROTATION_LOG).stream()
            .anyMatch(v -> Math.abs(v) > 0.1),
        "the robot never turned, so the rotation routine measured no rotation");
  }

  // The rotation routine's columns are the robot's, not a wheel's: its position has to leave the
  // half-turn a Rotation2d would fold it into.
  @Test
  void theRotationRoutineLogsAContinuousRobotAngle() {
    var yaw = log.values("position-" + MOTOR + "-" + ROTATION_LOG);

    assertTrue(
        yaw.stream().anyMatch(v -> Math.abs(v) > 0.5),
        "the logged robot angle never left the range a wrapped rotation would hold it in: "
            + yaw.stream().mapToDouble(Double::doubleValue).max().orElse(0));
  }

  // The one property that decides whether the analyser can do anything with this file. DataLog
  // stamps records with wpi::Now, the analyser reads velocity and acceleration out of those
  // stamps, and a harness on the wall clock writes a minute of ramp into a fraction of a second.
  @Test
  void theLogIsStampedInSimulatedTimeRatherThanWallClock() {
    var expected = DriveConstants.CHARACTERISATION_TIMEOUT.times(TESTS_PER_ROUTINE * ROUTINES);

    assertTrue(
        log.span().gte(expected.times(0.8)),
        "the log spans "
            + log.span().in(Seconds)
            + " s of stamps against "
            + expected
            + " of ramp");
  }

  // A step test has to start from rest. SysId measures its velocity delay as the gap between the
  // first sample carrying voltage and the first carrying near-peak acceleration, and while the
  // robot is still rolling the other way that product is negative — so a test begun on the move
  // reports a delay that is really the time spent stopping.
  @Test
  void everyDynamicTestStartsFromRest() {
    for (var routine : List.of(DRIVE_LOG, ROTATION_LOG)) {
      for (var test : List.of("dynamic-forward", "dynamic-reverse")) {
        double opening = log.firstValueOf("velocity-" + MOTOR + "-" + routine, routine, test);

        assertTrue(
            Math.abs(opening) < AT_REST,
            routine + " " + test + " opened at " + opening + ", so it began by stopping");
      }
    }
  }

  @Test
  void theCancellationPathZeroesTheOutputAndClosesEveryTest() {
    // What the routines commanded, not what the plant was handed: the steer array carries the
    // modelled loop's output for the two routines that close it, and that is never quite zero.
    assertEquals(0, lastDriveCommand, "a drive ramp voltage outlived the test that wrote it");
    assertEquals(0, lastSteerCommand, "the steer ramp voltage outlived the test that wrote it");
    for (var routine : List.of(DRIVE_LOG, STEER_LOG, ROTATION_LOG)) {
      assertEquals(
          "none",
          log.states(routine).getLast(),
          "the " + routine + " routine's state was left reading as a test still running");
    }
  }

  // The settle Drive.settledFirst puts in front of every ramp, which the routines themselves do
  // not carry. Without it a test starts from whatever speed the last one left the robot at, and a
  // step spends its first samples arresting that instead of accelerating from rest — which is
  // real data about a manoeuvre nobody is trying to characterise.
  private void run(Scheduler scheduler, Command command, Consumer<Voltage> output) {
    for (int cycle = 0; cycle < SETTLE_CYCLES; cycle++) {
      output.accept(Volts.zero());
      step(scheduler);
    }

    scheduler.schedule(command);
    while (scheduler.isScheduledOrRunning(command) && now.lte(BUDGET)) {
      step(scheduler);
    }
    assertFalse(
        scheduler.isScheduledOrRunning(command),
        "the routine outran the test's budget: " + command.name());
    // The timeout is a race, so the command handed to the scheduler ends a cycle before the body
    // it wraps is cancelled, and the plant needs a cycle after that to see the zero the
    // cancellation wrote rather than carrying the last ramp voltage into the next test.
    for (int cycle = 0; cycle < RELEASE_CYCLES; cycle++) {
      step(scheduler);
    }
  }

  // Everything Drive does between a routine and the plant, with the SPARK-side steer loop modelled
  // at its own rate. The steer routine is the one case with no loop to model: it writes volts.
  private void step(Scheduler scheduler) {
    // Before the scheduler, so a command that reads the clock on the cycle it starts sees the
    // cycle it is on rather than the previous one.
    SimHooks.stepTiming(STEP.in(Seconds));
    now = now.plus(STEP);
    scheduler.run();

    var previousAzimuth = state[0].azimuth();
    for (int substep = 0; substep < SUB_STEPS; substep++) {
      if (!steerOpenLoop) {
        for (int i = 0; i < MODULES; i++) {
          steerLoops[i].setSetpoint(steerSetpoints[i]);
          steerVolts[i] = steerLoops[i].calculate(sensorRotations(state[i].azimuth()), SUB_STEP);
        }
      }
      state = physics.update(driveVolts, steerVolts, SUB_STEP);
    }

    azimuthRate = state[0].azimuth().minus(previousAzimuth).getRotations() / STEP.in(Seconds);
    var rotation = physics.truePose().getRotation();
    yawRadians += rotation.minus(lastRotation).getRadians();
    lastRotation = rotation;
  }

  private static double sensorRotations(Rotation2d azimuth) {
    return MathUtil.inputModulus(azimuth.getRotations(), 0, 1);
  }

  private void commandDrive(Voltage volts) {
    lastDriveCommand = volts.in(Volts);
    Arrays.fill(driveVolts, lastDriveCommand);
    Arrays.fill(steerSetpoints, 0);
    steerOpenLoop = false;
  }

  private void commandRotation(Voltage volts) {
    lastDriveCommand = volts.in(Volts);
    Arrays.fill(driveVolts, lastDriveCommand);
    for (int i = 0; i < MODULES; i++) {
      steerSetpoints[i] = sensorRotations(spinAzimuths[i]);
    }
    steerOpenLoop = false;
  }

  // One module, and the drive axis at rest under it, exactly as Drive writes it.
  private void commandSteer(Voltage volts) {
    lastDriveCommand = 0;
    lastSteerCommand = volts.in(Volts);
    Arrays.fill(driveVolts, 0);
    Arrays.fill(steerVolts, 0);
    steerVolts[0] = lastSteerCommand;
    steerOpenLoop = true;
  }

  private void logDrive(SysIdRoutineLog motors) {
    motors
        .motor(MOTOR)
        .voltage(Volts.of(lastDriveCommand))
        .linearPosition(Meters.of(state[0].wheelPositionRad() * WHEEL_RADIUS))
        .linearVelocity(MetersPerSecond.of(state[0].wheelVelocityRadPerSec() * WHEEL_RADIUS));
  }

  private void logSteer(SysIdRoutineLog motors) {
    motors
        .motor(MOTOR)
        .voltage(Volts.of(lastSteerCommand))
        .angularPosition(state[0].azimuth().getMeasure())
        .angularVelocity(RotationsPerSecond.of(azimuthRate));
  }

  private void logRotation(SysIdRoutineLog motors) {
    motors
        .motor(MOTOR)
        .voltage(Volts.of(lastDriveCommand))
        .angularPosition(Radians.of(yawRadians))
        .angularVelocity(RadiansPerSecond.of(physics.trueVelocity().omega));
  }

  private static final class Wheels implements Mechanism {
    private final Scheduler scheduler;

    Wheels(Scheduler scheduler) {
      this.scheduler = scheduler;
    }

    @Override
    public Scheduler getRegisteredScheduler() {
      return scheduler;
    }
  }

  // A reader of only what the analyser reads: the entry names, the numbers under them, and the
  // test-state strings.
  private record Log(List<String> entryNames, List<Sample> samples) {
    private record Sample(String name, long timestampMicros, double value, String text) {}

    static Log read(Path file) throws IOException {
      var names = new ArrayList<String>();
      var samples = new ArrayList<Sample>();
      Map<Integer, String> entryNamesById = new HashMap<>();
      Map<Integer, String> typesById = new HashMap<>();
      var reader = new DataLogReader(file.toString());
      assertTrue(reader.isValid(), "the routine did not write a readable WPILOG");
      for (DataLogRecord record : reader) {
        if (record.isStart()) {
          var start = record.getStartData();
          entryNamesById.put(start.entry, start.name);
          typesById.put(start.entry, start.type);
          if (!names.contains(start.name)) {
            names.add(start.name);
          }
        } else if (!record.isControl()) {
          var name = entryNamesById.get(record.getEntry());
          var type = typesById.get(record.getEntry());
          if ("double".equals(type)) {
            samples.add(new Sample(name, record.getTimestamp(), record.getDouble(), null));
          } else if ("string".equals(type)) {
            samples.add(new Sample(name, record.getTimestamp(), 0, record.getString()));
          }
        }
      }
      return new Log(names, samples);
    }

    List<Double> values(String entry) {
      return samples.stream().filter(s -> s.name.equals(entry)).map(Sample::value).toList();
    }

    List<String> states(String logName) {
      return samples.stream()
          .filter(s -> s.name.equals("sysid-test-state-" + logName))
          .map(Sample::text)
          .toList();
    }

    // tools/sysid's DataSelector splits the state values: the first token has to be quasistatic
    // or dynamic and the last forward or reverse, or the entry is warned away and dropped.
    Set<String> tests(String logName) {
      var found = new LinkedHashSet<String>();
      for (var value : states(logName)) {
        var tokens = value.split("-");
        var first = tokens[0];
        var last = tokens[tokens.length - 1];
        if ((first.equals("quasistatic") || first.equals("dynamic"))
            && (last.equals("forward") || last.equals("reverse"))) {
          found.add(value);
        }
      }
      return found;
    }

    // The first sample of the named entry at or after the test's state value first appears.
    double firstValueOf(String entry, String logName, String test) {
      long start =
          samples.stream()
              .filter(sample -> sample.name.equals("sysid-test-state-" + logName))
              .filter(sample -> test.equals(sample.text))
              .mapToLong(Sample::timestampMicros)
              .min()
              .orElseThrow(() -> new AssertionError(test + " never ran on " + logName));
      return samples.stream()
          .filter(sample -> sample.name.equals(entry))
          .filter(sample -> sample.timestampMicros >= start)
          .mapToDouble(Sample::value)
          .findFirst()
          .orElseThrow(() -> new AssertionError("no " + entry + " after " + test));
    }

    Time span() {
      var stamps = samples.stream().mapToLong(Sample::timestampMicros);
      var summary = stamps.summaryStatistics();
      return Microseconds.of(summary.getMax() - summary.getMin());
    }

    Set<String> motorEntries() {
      return entryNames.stream()
          .filter(n -> n.contains("-" + MOTOR + "-"))
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
  }
}
