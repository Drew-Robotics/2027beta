// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Microseconds;
import static org.wpilib.units.Units.Seconds;

import java.util.Arrays;
import java.util.List;
import org.wpilib.backend.DataLogTelemetryBackend;
import org.wpilib.backend.NetworkTablesTelemetryBackend;
import org.wpilib.driverstation.DriverStation;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.system.DataLogManager;
import org.wpilib.system.RobotController;
import org.wpilib.system.WPILibVersion;
import org.wpilib.telemetry.MultiTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.util.Alert;
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
  private final TelemetryTable robotLog;
  private final TelemetryTable canLog;
  private final TelemetryTable alertLog;

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

    // A brownout that held for the whole match and a brownout signal that stopped being written
    // are otherwise the same bytes on disk.
    robotLog.keepDuplicates("BrownedOut");
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

    lastWakeUs = RobotController.getMonotonicTime();
    addPeriodic(this::logAlerts, Constants.ALERT_LOG_PERIOD.in(Seconds));
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

    var can = RobotController.getCANStatus(Constants.DRIVE_BUS);
    canLog.log("Utilization", can.percentBusUtilization);
    canLog.log("ReceiveErrors", can.receiveErrorCount);
    canLog.log("TransmitErrors", can.transmitErrorCount);
    canLog.log("BusOff", can.busOffCount);
    canLog.log("TxFull", can.txFullCount);
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
