// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.Inches;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Rotations;

import org.wpilib.framework.RobotBase;
import org.wpilib.hardware.bus.CANBus;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.Current;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Time;

public final class Constants {
  public static final Time LOOP_PERIOD = Milliseconds.of(5);

  // getAlerts() allocates and returns every alert on the robot, and an alert changes on a human
  // timescale, so this is the one signal not written every loop.
  public static final Time ALERT_LOG_PERIOD = Milliseconds.of(250);

  public static final CANBus DRIVE_BUS = CANBus.CAN_S0;

  // === SDS Mk5i, off the manufacturer's layout drawing =========================================

  // R2, the middle of the three pinions the module ships with. Swapping the pinion is the only
  // change the ratio needs, so the first stage is written as its tooth counts.
  public static final double DRIVE_REDUCTION = (54.0 / 14.0) * (25.0 / 32.0) * (30.0 / 15.0);
  public static final double STEER_REDUCTION = 26.0;

  // === Provisional =============================================================================
  // Nothing below this line has been measured, and no chassis exists to measure it on. Every
  // number here is a starting value that a bring-up session replaces.

  // Nominal for the 4 in wheel. Odometry wants the rolling radius under load, which is smaller.
  public static final Distance WHEEL_RADIUS = Inches.of(2);
  public static final Distance TRACK_WIDTH = Inches.of(23.5);
  public static final Distance WHEELBASE = Inches.of(23.5);

  public static final Current DRIVE_CURRENT_LIMIT = Amps.of(60);
  public static final Current STEER_CURRENT_LIMIT = Amps.of(40);

  public record DriveGains(double kP, double kS, double kV) {}

  // dFilter is REVLib's derivative filter. Its javadoc gives it no units and no range, so it is
  // tuned against kD rather than after it.
  public record SteerGains(double kP, double kD, double dFilter) {}

  public static final class Real {
    // No characterisation has run. kV is 12 V over the free speed at this reduction and kS is a
    // guess; both are the nameplate rather than a measurement.
    public static final DriveGains DRIVE_MOTOR = new DriveGains(0.05, 0.15, 2.0);
    public static final SteerGains STEER_MOTOR = new SteerGains(3.0, 0.05, 0.0);

    private Real() {}
  }

  public static final class Sim {
    // Chosen so the model tracks its setpoint. These are not a prediction of the real robot's
    // gains, and turning them until a test passes turns that test into a tautology.
    public static final DriveGains DRIVE_MOTOR = new DriveGains(0.1, 0.0, 2.0);
    public static final SteerGains STEER_MOTOR = new SteerGains(8.0, 0.0, 0.0);

    private Sim() {}
  }

  public record SwerveModuleConfig(
      String name, int driveId, int steerId, Angle steerZeroOffset, Translation2d location) {}

  public record DriveConfig(
      SwerveModuleConfig frontLeft,
      SwerveModuleConfig frontRight,
      SwerveModuleConfig backLeft,
      SwerveModuleConfig backRight,
      int gyroId) {}

  private static final Distance HALF_TRACK = TRACK_WIDTH.div(2);
  private static final Distance HALF_BASE = WHEELBASE.div(2);

  public static final DriveConfig DRIVE =
      new DriveConfig(
          new SwerveModuleConfig(
              "FrontLeft", 1, 2, Rotations.of(0), new Translation2d(HALF_BASE, HALF_TRACK)),
          new SwerveModuleConfig(
              "FrontRight",
              3,
              4,
              Rotations.of(0),
              new Translation2d(HALF_BASE, HALF_TRACK.unaryMinus())),
          new SwerveModuleConfig(
              "BackLeft",
              5,
              6,
              Rotations.of(0),
              new Translation2d(HALF_BASE.unaryMinus(), HALF_TRACK)),
          new SwerveModuleConfig(
              "BackRight",
              7,
              8,
              Rotations.of(0),
              new Translation2d(HALF_BASE.unaryMinus(), HALF_TRACK.unaryMinus())),
          9);

  public static DriveGains driveGains() {
    return RobotBase.isSimulation() ? Sim.DRIVE_MOTOR : Real.DRIVE_MOTOR;
  }

  public static SteerGains steerGains() {
    return RobotBase.isSimulation() ? Sim.STEER_MOTOR : Real.STEER_MOTOR;
  }

  private Constants() {}
}
