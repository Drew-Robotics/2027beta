// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Celsius;
import static org.wpilib.units.Units.Joules;
import static org.wpilib.units.Units.Microseconds;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Seconds;

import first.robot.mechanisms.Drive;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.wpilib.backend.DataLogTelemetryBackend;
import org.wpilib.backend.NetworkTablesTelemetryBackend;
import org.wpilib.command3.Scheduler;
import org.wpilib.command3.button.CommandGamepad;
import org.wpilib.driverstation.DriverStation;
import org.wpilib.driverstation.MatchState;
import org.wpilib.driverstation.RobotState;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.hardware.power.PowerDistribution;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.system.DataLogManager;
import org.wpilib.system.Filesystem;
import org.wpilib.system.RobotController;
import org.wpilib.system.WPILibVersion;
import org.wpilib.telemetry.MultiTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.util.Alert;
import org.wpilib.util.Alert.Level;
import org.wpilib.util.AlertDataJNI;
import org.wpilib.util.AlertDataJNI.AlertInfo;

/**
 * The methods in this class are called automatically as described in the OpModeRobot documentation.
 * OpMode classes anywhere in the package (or sub-packages) where this class is located are
 * automatically registered to display in the Driver Station. If you change the name of this class
 * or the package after creating this project, you must also update the Main.java file in the
 * project.
 */
public class Robot extends OpModeRobot {
  public final Drive drive;
  public final PoseEstimator poseEstimator;
  public final TrajectoryLoader trajectories;
  public final CommandGamepad driver = new CommandGamepad(Constants.DRIVER_PORT);

  private final TelemetryTable robotLog;
  private final TelemetryTable canLog;
  private final TelemetryTable alertLog;
  private final TelemetryTable radioLog;
  private final TelemetryTable matchLog;
  private final TelemetryTable railLog;
  private final TelemetryTable pdhLog;

  // Nothing this class does may take out the constructor, and a hub that is not on the bus is the
  // ordinary state of a robot on a bench.
  private final PowerDistribution pdh;

  // Toggled every loop rather than fired once, so it clears itself when the alliance turns up.
  private final Alert allianceUnknown =
      new Alert("alliance-unknown", "Driver Station attached with no alliance", Level.HIGH);

  // A non-default state that decides which half of the field the robot drives at belongs in front
  // of the operator before the match, not in the log afterwards.
  private final Alert pathsMirrored =
      new Alert("paths-mirrored", "Paths are mirrored across the field's long axis", Level.LOW);

  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(
              java.time.Duration.ofMillis((long) Constants.RADIO_TIMEOUT.in(Milliseconds)))
          .build();
  private CompletableFuture<HttpResponse<String>> radioStatus;

  private long lastWakeUs;

  /**
   * This function is run when the robot is first started up and should be used for any
   * initialization code.
   */
  @SuppressWarnings("this-escape")
  public Robot() {
    super(Constants.LOOP_PERIOD.in(Seconds));

    // getLog() starts the manager and NetworkTables capture defaults to on, so flipping the flag
    // after it would write every telemetry signal twice for the window in between.
    DataLogManager.logNetworkTables(false);
    var dataLog = DataLogManager.getLog();
    DriverStation.startDataLog(dataLog, true);

    // Registering at the root prefix closes the backend it displaces, which is the NetworkTables
    // one the framework installed. Leaving it out of this multi turns dashboards off rather than
    // leaving them alone.
    TelemetryRegistry.registerBackend(
        "",
        new MultiTelemetryBackend(
            new NetworkTablesTelemetryBackend(NetworkTableInstance.getDefault(), "/Telemetry"),
            new DataLogTelemetryBackend(dataLog, "/Telemetry")));

    robotLog = TelemetryRegistry.getTable("/Robot");
    canLog = robotLog.getTable("Can").getTable("Bus0");
    alertLog = robotLog.getTable("Alerts");
    radioLog = robotLog.getTable("Radio");
    matchLog = TelemetryRegistry.getTable("/Match");
    railLog = robotLog.getTable("Rail3V3");
    pdhLog = robotLog.getTable("Pdh");
    pdh = openPdh();

    // A brownout that held for the whole match and a brownout signal that stopped being written
    // are otherwise the same bytes on disk.
    robotLog.keepDuplicates("BrownedOut");
    radioLog.keepDuplicates("Connected");
    // A property value has to be JSON, so the symbol is quoted inside the string.
    alertLog.setProperty("StartTimes", "unit", "\"s\"");

    logMetadata(TelemetryRegistry.getTable("/Metadata"));

    if (BuildMetadata.GIT_DIRTY) {
      new Alert(
              "deploy-dirty",
              "Deployed from a dirty tree at " + BuildMetadata.GIT_SHA,
              Alert.Level.LOW)
          .set(true);
    }

    // Parsed here rather than at enable: a cold parse costs 63 ms against 0.135 ms for a cached
    // lookup, and the framework offers no once-at-startup hook to pay it in but this constructor.
    trajectories = new TrajectoryLoader(Filesystem.getDeployDirectory().toPath());

    drive =
        new Drive(
            DriveConstants.DRIVE,
            this::trajectory,
            // A method reference, not a lambda over the field: the estimator is built from the
            // drive base two statements below this one, so the field is still blank here.
            this::estimatedPose,
            TelemetryRegistry.getTable("/Drive"),
            TelemetryRegistry.getTable("/Auto"),
            TelemetryRegistry.getTable("/Sim"),
            Scheduler.getDefault());

    // Seeded from the hardware rather than from zeroes, so the first odometry update measures a
    // step the wheels actually took.
    poseEstimator =
        new PoseEstimator(
            drive.getKinematics(),
            drive.getGyroHeading(),
            drive.getModulePositions(),
            TelemetryRegistry.getTable("/Drive"));

    // The safe state of the whole robot is one block a reviewer can read, rather than something
    // they have to know idle() would have defaulted to.
    drive.setDefaultCommand(drive.idle());

    lastWakeUs = RobotController.getMonotonicTime();
    // The first sendAsync costs ~8 ms while the client starts its machinery, which is over the
    // whole loop period. Paid here, where nothing is timing anything.
    radioStatus = request();

    addPeriodic(this::logAlerts, Constants.ALERT_LOG_PERIOD.in(Seconds));
    addPeriodic(this::logRadio, Constants.RADIO_LOG_PERIOD.in(Seconds));
  }

  private Pose2d estimatedPose() {
    return poseEstimator.getEstimatedPose();
  }

  // The cache holds every path exactly as it was authored, and both transforms are applied here —
  // on a lookup, which autonomous makes once per schedule and therefore once per enable. They
  // commute, so this ordering is a reading preference and nothing else.
  public HolonomicTrajectory trajectory(String name) {
    return FieldConstants.forSide(FieldConstants.forAlliance(trajectories.get(name)));
  }

  private static PowerDistribution openPdh() {
    try {
      return new PowerDistribution(Constants.CAN_BUS);
    } catch (RuntimeException e) {
      new Alert("power-distribution", "No power distribution hub: " + e.getMessage(), Level.LOW)
          .set(true);
      return null;
    }
  }

  @Override
  public void robotPeriodic() {
    long wake = getLoopStartTime();
    robotLog.log("LoopDelta", Microseconds.of(wake - lastWakeUs));
    lastWakeUs = wake;

    robotLog.log("BatteryVoltage", RobotController.getMeasureBatteryVoltage());
    robotLog.log("BrownedOut", RobotController.isBrownedOut());
    robotLog.log("CommsDisableCount", RobotController.getCommsDisableCount());
    robotLog.log("CpuTemp", RobotController.getMeasureCPUTemp());
    robotLog.log("InputVoltage", RobotController.getMeasureInputVoltage());
    robotLog.log("SysActive", RobotController.isSysActive());

    // The 3.3 V rail feeds the sensors. Its fault count is what separates a sensor that failed
    // from a rail that browned out under it.
    railLog.log("Voltage", RobotController.getMeasureVoltage3V3());
    railLog.log("Current", RobotController.getMeasureCurrent3V3());
    railLog.log("FaultCount", RobotController.getFaultCount3V3());

    if (pdh != null) {
      robotLog.log("Pdh", pdh);
      pdhLog.log("Temperature", Celsius.of(pdh.getTemperature()));
      pdhLog.log("TotalEnergy", Joules.of(pdh.getTotalEnergy()));
    }

    // Read every loop, never once. driverStationConnected() fires on the control word's
    // DS-attached bit, and the alliance station arrives from the FMS some time after that.
    var alliance = MatchState.getAlliance();
    matchLog.log("Alliance", alliance.map(Enum::name).orElse("Unknown"));
    matchLog.log("Station", MatchState.getLocation().orElse(0));
    matchLog.log("FmsAttached", RobotState.isFMSAttached());
    matchLog.log("EventName", MatchState.getEventName());
    matchLog.log("MatchType", MatchState.getMatchType().name());
    matchLog.log("MatchNumber", MatchState.getMatchNumber());
    matchLog.log("ReplayNumber", MatchState.getReplayNumber());
    matchLog.log("GameData", MatchState.getGameData().orElse(""));
    matchLog.log("TimeRemaining", Seconds.of(MatchState.getMatchTime()));

    boolean mirrored = FieldConstants.MIRRORED.getAsBoolean();
    matchLog.log("Mirrored", mirrored);
    pathsMirrored.set(mirrored);

    // A DS that is attached and has not said which alliance it is means every alliance-dependent
    // decision on the robot is about to be made against a guess.
    allianceUnknown.set(RobotState.isDSAttached() && alliance.isEmpty());

    drive.updateYawRateHistory();
    drive.log();

    // Before the scheduler, and the order is load-bearing: every command that runs below reads a
    // pose built from this iteration's module positions rather than the previous one's.
    poseEstimator.odometryUpdate(drive.getGyroHeading(), drive.getModulePositions());
    poseEstimator.log();

    // Nothing in OpModeRobot runs the Commands v3 scheduler, so a mechanism whose scheduler is
    // never run has a default command that never starts and commands that never execute.
    Scheduler.getDefault().run();

    var can = RobotController.getCANStatus(Constants.CAN_BUS);
    canLog.log("Utilization", can.percentBusUtilization);
    canLog.log("ReceiveErrors", can.receiveErrorCount);
    canLog.log("TransmitErrors", can.transmitErrorCount);
    canLog.log("BusOff", can.busOffCount);
    canLog.log("TxFull", can.txFullCount);
  }

  @Override
  public void simulationInit() {
    drive.simulationInit();
  }

  @Override
  public void simulationPeriodic() {
    drive.updateSim();
  }

  /** This function is called exactly once when the DS first connects. */
  @Override
  public void driverStationConnected() {}

  /**
   * This function is called periodically anytime when no opmode is selected, including when the
   * Driver Station is disconnected.
   */
  @Override
  public void nonePeriodic() {}

  // The request is never waited on: it is started on one callback and read on a later one, so a
  // radio that has gone away costs this thread nothing rather than its timeout.
  private void logRadio() {
    if (!radioStatus.isDone()) {
      return;
    }
    var response = radioStatus.getNow(null);
    radioLog.log("Connected", response != null && response.statusCode() == 200);
    radioLog.log("Status", response == null ? "" : response.body());
    radioStatus = request();
  }

  private CompletableFuture<HttpResponse<String>> request() {
    return http.sendAsync(
            HttpRequest.newBuilder(Constants.RADIO_STATUS)
                .timeout(
                    java.time.Duration.ofMillis((long) Constants.RADIO_TIMEOUT.in(Milliseconds)))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .exceptionally(e -> null);
  }

  private void logAlerts() {
    // Every constructed Alert is in the array whether or not it ever fired, and a zero start time
    // is what "inactive" means.
    List<AlertInfo> active =
        Arrays.stream(AlertDataJNI.getAlerts()).filter(a -> a.activeStartTime != 0).toList();

    alertLog.log("Ids", active.stream().map(a -> a.group + "/" + a.id).toArray(String[]::new));
    alertLog.log("Texts", active.stream().map(a -> a.text).toArray(String[]::new));
    alertLog.log("Levels", active.stream().map(Robot::levelName).toArray(String[]::new));
    alertLog.log(
        "StartTimes",
        active.stream().mapToDouble(a -> Microseconds.of(a.activeStartTime).in(Seconds)).toArray());
  }

  private static String levelName(AlertInfo alert) {
    return switch (alert.level) {
      case AlertDataJNI.LEVEL_HIGH -> "HIGH";
      case AlertDataJNI.LEVEL_MEDIUM -> "MEDIUM";
      case AlertDataJNI.LEVEL_LOW -> "LOW";
      default -> "UNKNOWN";
    };
  }

  private static void logMetadata(TelemetryTable log) {
    log.log("GitSha", BuildMetadata.GIT_SHA);
    log.log("GitDirty", BuildMetadata.GIT_DIRTY);
    log.log("Branch", BuildMetadata.BRANCH);
    log.log("BuildTime", BuildMetadata.BUILD_TIME);
    log.log("WpilibVersion", WPILibVersion.Version);
    log.log("RevLibVersion", BuildMetadata.REVLIB_VERSION);
    log.log("PhoenixVersion", BuildMetadata.PHOENIX_VERSION);
    log.log("Serial", RobotController.getSerialNumber());
    log.log("TeamNumber", BuildMetadata.TEAM_NUMBER);
  }
}
