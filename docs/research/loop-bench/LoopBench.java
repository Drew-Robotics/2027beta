// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package wpilib.robot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.wpilib.backend.DataLogTelemetryBackend;
import org.wpilib.backend.NetworkTablesTelemetryBackend;
import org.wpilib.datalog.DataLogBackgroundWriter;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.estimator.SwerveDrivePoseEstimator3d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.system.Timer;
import org.wpilib.telemetry.Telemetry;
import org.wpilib.telemetry.MultiTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;

/**
 * Measures what a swerve-shaped workload costs per loop on real SystemCore hardware, at whatever
 * period /home/systemcore/loopbench.period names.
 */
public class LoopBench extends OpModeRobot {
  private static final double MAX_SPEED = 4.5;
  private static final double TRACK = 0.29;
  private static final int PHASE_SAMPLES = 3000;

  private final double m_period;
  private final SwerveDriveKinematics m_kinematics =
      new SwerveDriveKinematics(
          new Translation2d(TRACK, TRACK),
          new Translation2d(TRACK, -TRACK),
          new Translation2d(-TRACK, TRACK),
          new Translation2d(-TRACK, -TRACK));
  private final SwerveModulePosition[] m_positions = {
    new SwerveModulePosition(), new SwerveModulePosition(),
    new SwerveModulePosition(), new SwerveModulePosition()
  };
  private final SwerveDrivePoseEstimator3d m_estimator;
  private final TelemetryTable m_table = Telemetry.getTable("LoopBench");

  // 0 warmup, 1 idle, 2 math, 3 math+NT, 4 math+NT+WPILOG, 5 done
  private DataLogBackgroundWriter m_writer;
  private long m_bytesAtPhaseStart;
  private long m_nanosAtPhaseStart;
  private int m_phase;
  private int m_count;
  private final long[] m_work = new long[PHASE_SAMPLES];
  private final long[] m_wake = new long[PHASE_SAMPLES];
  private long m_lastWake;
  private double m_t;

  /** Reads the period from disk so one deploy can be measured at several rates. */
  public LoopBench() {
    this(readPeriod());
  }

  private LoopBench(double period) {
    super(period);
    m_period = period;
    m_estimator =
        new SwerveDrivePoseEstimator3d(
            m_kinematics, Rotation3d.ZERO, m_positions, Pose3d.ZERO);
    System.out.println("LOOPBENCH period=" + m_period);
  }

  private static double readPeriod() {
    try {
      return Double.parseDouble(
          Files.readString(Path.of("/home/systemcore/loopbench.period")).trim());
    } catch (IOException | NumberFormatException e) {
      return DEFAULT_PERIOD;
    }
  }

  @Override
  public void robotPeriodic() {
    long wake = System.nanoTime();
    long start = wake;

    if (m_phase >= 2 && m_phase <= 4) {
      swerveWorkload();
      if (m_phase >= 3) {
        logEverySignal();
      }
    }

    long end = System.nanoTime();
    record(wake, start, end);
  }

  private void swerveWorkload() {
    m_t += m_period;
    var target =
        new ChassisVelocities(
            3.0 * Math.cos(m_t), 3.0 * Math.sin(m_t), 4.0 * Math.sin(m_t * 0.5));
    var velocities =
        SwerveDriveKinematics.desaturateWheelVelocities(
            m_kinematics.toSwerveModuleVelocities(target.discretize(m_period)), MAX_SPEED);
    for (int i = 0; i < 4; i++) {
      SwerveModuleVelocity optimized = velocities[i].optimize(m_positions[i].angle);
      m_positions[i].distance += optimized.velocity * m_period;
      m_positions[i].angle = optimized.angle;
      velocities[i] = optimized;
    }
    m_estimator.updateWithTime(
        Timer.getMonotonicTimestamp(), new Rotation3d(0, 0, m_t * 0.3), m_positions);
  }

  /** Roughly what issue #11 asks for: every signal a swerve base would publish, every loop. */
  private void logEverySignal() {
    var pose = m_estimator.getEstimatedPosition();
    m_table.log("Pose/X", pose.getX());
    m_table.log("Pose/Y", pose.getY());
    m_table.log("Pose/Z", pose.getZ());
    m_table.log("Pose/Roll", pose.getRotation().getX());
    m_table.log("Pose/Pitch", pose.getRotation().getY());
    m_table.log("Pose/Yaw", pose.getRotation().getZ());
    for (int i = 0; i < 4; i++) {
      String m = "Module" + i + "/";
      m_table.log(m + "DriveDistance", m_positions[i].distance);
      m_table.log(m + "SteerAngle", m_positions[i].angle.getRadians());
      m_table.log(m + "DriveVelocity", m_positions[i].distance * 0.5);
      m_table.log(m + "DriveSetpoint", m_positions[i].distance * 0.4);
      m_table.log(m + "SteerSetpoint", m_positions[i].angle.getDegrees());
      m_table.log(m + "DriveCurrent", 12.0 + i);
      m_table.log(m + "SteerCurrent", 3.0 + i);
      m_table.log(m + "DriveVoltage", 8.0 + i);
      m_table.log(m + "SteerVoltage", 2.0 + i);
      m_table.log(m + "DriveTemp", 40.0 + i);
      m_table.log(m + "AbsoluteEncoder", m_positions[i].angle.getRotations());
    }
    m_table.log("Gyro/Yaw", m_t * 0.3);
    m_table.log("Gyro/Rate", 0.3);
    m_table.log("Battery/Voltage", 12.4);
    m_table.log("Battery/Current", 40.0);
    m_table.log("Loop/Timestamp", Timer.getMonotonicTimestamp());
  }

  private void record(long wake, long start, long end) {
    switch (m_phase) {
      case 0, 1 -> {
        m_count++;
        if (m_count >= (int) (3.0 / m_period)) {
          m_phase++;
          m_count = 0;
          m_lastWake = 0;
        }
      }
      case 2, 3, 4 -> {
        if (m_lastWake != 0) {
          m_wake[m_count] = wake - m_lastWake;
          m_work[m_count] = end - start;
          m_count++;
        }
        m_lastWake = wake;
        if (m_count >= PHASE_SAMPLES) {
          report(
              switch (m_phase) {
                case 2 -> "MATH_ONLY";
                case 3 -> "MATH_PLUS_NT";
                default -> "MATH_PLUS_NT_PLUS_WPILOG";
              });
          m_phase++;
          m_count = 0;
          m_lastWake = 0;
          if (m_phase == 4) {
            startWpilog();
          } else if (m_phase == 5) {
            reportBytes();
          }
        }
      }
      default -> { }
    }
  }

  private void startWpilog() {
    m_writer = new DataLogBackgroundWriter("/home/systemcore/loopbench-logs", "bench.wpilog");
    TelemetryRegistry.registerBackend(
        "",
        new MultiTelemetryBackend(
            new NetworkTablesTelemetryBackend(NetworkTableInstance.getDefault(), "/Telemetry"),
            new DataLogTelemetryBackend(m_writer, "/Telemetry")));
    m_bytesAtPhaseStart = logBytes();
    m_nanosAtPhaseStart = System.nanoTime();
    System.out.println("LOOPBENCH wpilog backend registered");
  }

  private void reportBytes() {
    m_writer.flush();
    long grown = logBytes() - m_bytesAtPhaseStart;
    double seconds = (System.nanoTime() - m_nanosAtPhaseStart) / 1e9;
    System.out.printf(
        "LOOPBENCH WPILOG period=%.4f bytes=%d seconds=%.1f rate=%.1f KB/s"
            + " -> match(150s)=%.1f MB%n",
        m_period, grown, seconds, grown / seconds / 1024.0, grown / seconds * 150 / 1048576.0);
  }

  private static long logBytes() {
    try {
      return Files.walk(Path.of("/home/systemcore/loopbench-logs"))
          .filter(Files::isRegularFile)
          .mapToLong(
              q -> {
                try {
                  return Files.size(q);
                } catch (IOException e) {
                  return 0L;
                }
              })
          .sum();
    } catch (IOException e) {
      return 0L;
    }
  }

  private void report(String label) {
    long[] work = Arrays.copyOf(m_work, PHASE_SAMPLES);
    long[] wake = Arrays.copyOf(m_wake, PHASE_SAMPLES);
    Arrays.sort(work);
    Arrays.sort(wake);
    System.out.printf(
        "LOOPBENCH %s period=%.4f n=%d work_us p50=%.3f p95=%.3f p99=%.3f max=%.3f"
            + " | wake_ms p50=%.3f p95=%.3f p99=%.3f max=%.3f%n",
        label,
        m_period,
        PHASE_SAMPLES,
        pct(work, 50) / 1000.0,
        pct(work, 95) / 1000.0,
        pct(work, 99) / 1000.0,
        work[PHASE_SAMPLES - 1] / 1000.0,
        pct(wake, 50) / 1e6,
        pct(wake, 95) / 1e6,
        pct(wake, 99) / 1e6,
        wake[PHASE_SAMPLES - 1] / 1e6);
  }

  private static long pct(long[] sorted, int p) {
    return sorted[Math.min(sorted.length - 1, (int) ((long) p * sorted.length / 100))];
  }
}
