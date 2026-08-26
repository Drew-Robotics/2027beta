// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package wpilib.robot;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.wpilib.backend.DataLogTelemetryBackend;
import org.wpilib.backend.NetworkTablesTelemetryBackend;
import org.wpilib.datalog.DataLogBackgroundWriter;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.math.estimator.SwerveDrivePoseEstimator3d;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import org.wpilib.math.controller.PIDController;
import org.wpilib.math.trajectory.HolonomicSample;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.system.Threads;
import org.wpilib.system.Timer;
import org.wpilib.telemetry.MultiTelemetryBackend;
import org.wpilib.telemetry.Telemetry;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;

import static org.wpilib.units.Units.Amps;
import static org.wpilib.units.Units.Celsius;
import static org.wpilib.units.Units.Degrees;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Radians;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.Rotations;
import static org.wpilib.units.Units.Seconds;
import static org.wpilib.units.Units.Volts;

/**
 * Match-length loop-jitter bench for issue #31. Same swerve workload as LoopBench, but one long
 * continuous phase so the GC and JIT tails have somewhere to show up, plus GC and allocation
 * accounting so a pause can be attributed rather than guessed at.
 */
public class LoopBenchGc extends OpModeRobot {
  private static final double MAX_SPEED = 4.5;
  private static final double TRACK = 0.29;
  private static final double DEFAULT_PERIOD = 0.005;
  private static final int DEFAULT_SAMPLES = 30000;

  private final double m_period;
  private final int m_samples;
  private final String m_label;
  private final boolean m_measures;
  private final int m_enableAtSample;
  private final boolean m_preload;
  private final boolean m_reuse;
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

  private final long[] m_work;
  private final long[] m_wake;
  private DataLogBackgroundWriter m_writer;
  private long m_lastWake;
  private int m_count;
  private boolean m_primed;
  private boolean m_done;
  private int m_prioAfter = -1;
  private double m_t;
  private long m_startNanos;
  private long m_allocAtStart;
  private Map<String, Long> m_allocPerThreadAtStart;
  private Map<String, long[]> m_gcAtStart;
  private long m_enableCostNanos;
  private int m_enableSample = -1;
  private HolonomicTrajectory m_traj;
  private ChassisVelocities m_firstOutput;

  public LoopBenchGc() {
    this(readDouble("loopbench.period", DEFAULT_PERIOD));
  }

  private LoopBenchGc(double period) {
    super(period);
    m_period = period;
    m_samples = (int) readDouble("loopbench.samples", DEFAULT_SAMPLES);
    m_label = readString("loopbench.label", "unlabeled");
    m_measures = readDouble("loopbench.measures", 0) > 0;
    m_enableAtSample = (int) (readDouble("loopbench.enableat", 0) / period);
    m_preload = readDouble("loopbench.preload", 0) > 0;
    m_reuse = readDouble("loopbench.reuse", 0) > 0;
    m_work = new long[m_samples];
    m_wake = new long[m_samples];
    m_estimator =
        new SwerveDrivePoseEstimator3d(m_kinematics, Rotation3d.ZERO, m_positions, Pose3d.ZERO);
    m_writer = new DataLogBackgroundWriter("/home/systemcore/loopbench-logs", "bench.wpilog");
    TelemetryRegistry.registerBackend(
        "",
        new MultiTelemetryBackend(
            new NetworkTablesTelemetryBackend(NetworkTableInstance.getDefault(), "/Telemetry"),
            new DataLogTelemetryBackend(m_writer, "/Telemetry")));
    if (m_preload) {
      long t0 = System.nanoTime();
      simulateAutoEnable();
      System.out.printf(
          "LOOPBENCH_GC preload_at_init took=%.3fms%n", (System.nanoTime() - t0) / 1e6);
    }
    System.out.printf(
        "LOOPBENCH_GC start label=%s period=%.4f samples=%d measures=%b flags=%s%n",
        m_label, m_period, m_samples, m_measures, ManagementFactory.getRuntimeMXBean().getInputArguments());
  }

  private static String readString(String name, String fallback) {
    try {
      return Files.readString(Path.of("/home/systemcore/" + name)).trim();
    } catch (IOException e) {
      return fallback;
    }
  }

  private static double readDouble(String name, double fallback) {
    try {
      return Double.parseDouble(readString(name, ""));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  @Override
  @SuppressWarnings({"deprecation", "removal"})
  public void robotPeriodic() {
    long wake = System.nanoTime();

    if (!m_primed) {
      m_primed = true;
      int want = (int) readDouble("loopbench.priority", 0);
      if (want > 0) {
        Threads.setCurrentThreadPriority(want);
      }
      m_prioAfter = Threads.getCurrentThreadPriority();
      m_startNanos = wake;
      m_allocAtStart = allocatedBytes();
      m_allocPerThreadAtStart = allocPerThread();
      m_gcAtStart = gcCounters();
    }

    if (m_done) {
      return;
    }

    if (m_enableAtSample > 0 && m_count == m_enableAtSample) {
      long t0 = System.nanoTime();
      simulateAutoEnable();
      m_enableCostNanos = System.nanoTime() - t0;
      m_enableSample = m_count;
    }

    swerveWorkload();
    if (m_measures) {
      logEverySignalAsMeasures();
    } else {
      logEverySignal();
    }
    long end = System.nanoTime();

    if (m_lastWake != 0) {
      m_wake[m_count] = wake - m_lastWake;
      m_work[m_count] = end - wake;
      m_count++;
    }
    m_lastWake = wake;

    if (m_count >= m_samples) {
      m_done = true;
      report();
    }
  }

  /**
   * What actually happens when autonomous is enabled: the trajectory is read and parsed, the
   * follower and its controllers are built, and the first sample is followed. Every one of those
   * paths is cold the first time.
   */
  private void simulateAutoEnable() {
    try {
      // #17 decision, modelled honestly: the cached object is reused, so enable does no I/O and
      // no parsing at all — only the follower construction and the first sample.
      HolonomicTrajectory traj =
          m_reuse && m_traj != null
              ? m_traj
              : HolonomicTrajectory.loadFromFile("/home/systemcore/test.traj");
      m_traj = traj;
      var x = new PIDController(5.0, 0.0, 0.0, m_period);
      var y = new PIDController(5.0, 0.0, 0.0, m_period);
      var theta = new PIDController(3.0, 0.0, 0.0, m_period);
      HolonomicSample first = traj.sampleAt(0.0);
      var pose = m_estimator.getEstimatedPosition().toPose2d();
      m_firstOutput =
          new ChassisVelocities(
              first.velocity.vx + x.calculate(pose.getX(), first.pose.getX()),
              first.velocity.vy + y.calculate(pose.getY(), first.pose.getY()),
              first.velocity.omega
                  + theta.calculate(
                      pose.getRotation().getRadians(), first.pose.getRotation().getRadians()));
    } catch (Exception e) {
      System.out.println("LOOPBENCH_GC enable_failed " + e);
    }
  }

  private void swerveWorkload() {
    m_t += m_period;
    var target =
        new ChassisVelocities(3.0 * Math.cos(m_t), 3.0 * Math.sin(m_t), 4.0 * Math.sin(m_t * 0.5));
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

  /** Same fifty signals, but as Measure objects — this is what issue #11's rule actually costs. */
  private void logEverySignalAsMeasures() {
    var pose = m_estimator.getEstimatedPosition();
    m_table.log("Pose/X", Meters.of(pose.getX()));
    m_table.log("Pose/Y", Meters.of(pose.getY()));
    m_table.log("Pose/Z", Meters.of(pose.getZ()));
    m_table.log("Pose/Roll", Radians.of(pose.getRotation().getX()));
    m_table.log("Pose/Pitch", Radians.of(pose.getRotation().getY()));
    m_table.log("Pose/Yaw", Radians.of(pose.getRotation().getZ()));
    for (int i = 0; i < 4; i++) {
      String m = "Module" + i + "/";
      m_table.log(m + "DriveDistance", Meters.of(m_positions[i].distance));
      m_table.log(m + "SteerAngle", Radians.of(m_positions[i].angle.getRadians()));
      m_table.log(m + "DriveVelocity", MetersPerSecond.of(m_positions[i].distance * 0.5));
      m_table.log(m + "DriveSetpoint", MetersPerSecond.of(m_positions[i].distance * 0.4));
      m_table.log(m + "SteerSetpoint", Degrees.of(m_positions[i].angle.getDegrees()));
      m_table.log(m + "DriveCurrent", Amps.of(12.0 + i));
      m_table.log(m + "SteerCurrent", Amps.of(3.0 + i));
      m_table.log(m + "DriveVoltage", Volts.of(8.0 + i));
      m_table.log(m + "SteerVoltage", Volts.of(2.0 + i));
      m_table.log(m + "DriveTemp", Celsius.of(40.0 + i));
      m_table.log(m + "AbsoluteEncoder", Rotations.of(m_positions[i].angle.getRotations()));
    }
    m_table.log("Gyro/Yaw", Radians.of(m_t * 0.3));
    m_table.log("Gyro/Rate", RadiansPerSecond.of(0.3));
    m_table.log("Battery/Voltage", Volts.of(12.4));
    m_table.log("Battery/Current", Amps.of(40.0));
    m_table.log("Loop/Timestamp", Seconds.of(Timer.getMonotonicTimestamp()));
  }

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

  private static long allocatedBytes() {
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long total = 0;
    for (long id : bean.getAllThreadIds()) {
      long bytes = bean.getThreadAllocatedBytes(id);
      if (bytes > 0) {
        total += bytes;
      }
    }
    return total;
  }

  private static Map<String, Long> allocPerThread() {
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    Map<String, Long> out = new LinkedHashMap<>();
    for (var info : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
      long bytes = bean.getThreadAllocatedBytes(info.getThreadId());
      if (bytes > 0) {
        out.merge(info.getThreadName(), bytes, Long::sum);
      }
    }
    return out;
  }

  private static Map<String, long[]> gcCounters() {
    Map<String, long[]> out = new LinkedHashMap<>();
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      out.put(bean.getName(), new long[] {bean.getCollectionCount(), bean.getCollectionTime()});
    }
    return out;
  }

  private void report() {
    double elapsed = (System.nanoTime() - m_startNanos) / 1e9;
    long allocated = allocatedBytes() - m_allocAtStart;
    long[] work = Arrays.copyOf(m_work, m_count);
    long[] wake = Arrays.copyOf(m_wake, m_count);

    long periodNanos = (long) (m_period * 1e9);
    int over1ms = 0;
    int over5ms = 0;
    int overDouble = 0;
    List<String> worst = new ArrayList<>();
    long[] indexed = new long[m_count];
    System.arraycopy(wake, 0, indexed, 0, m_count);
    for (int i = 0; i < m_count; i++) {
      long excess = wake[i] - periodNanos;
      if (excess > 1_000_000L) {
        over1ms++;
      }
      if (excess > 5_000_000L) {
        over5ms++;
      }
      if (wake[i] > 2 * periodNanos) {
        overDouble++;
      }
    }
    long warmupMax = 0;
    long steadyMax = 0;
    int warmupSamples = Math.min(m_count, (int) (10.0 / m_period));
    for (int i = 0; i < m_count; i++) {
      if (i < warmupSamples) {
        warmupMax = Math.max(warmupMax, wake[i]);
      } else {
        steadyMax = Math.max(steadyMax, wake[i]);
      }
    }
    Integer[] order = new Integer[m_count];
    for (int i = 0; i < m_count; i++) {
      order[i] = i;
    }
    Arrays.sort(order, (a, b) -> Long.compare(indexed[b], indexed[a]));
    for (int i = 0; i < Math.min(10, m_count); i++) {
      int idx = order[i];
      worst.add(String.format("%.2fms@t=%.1fs", indexed[idx] / 1e6, idx * m_period));
    }

    Arrays.sort(work);
    Arrays.sort(wake);
    System.out.printf(
        "LOOPBENCH_GC RESULT label=%s period=%.4f prio=%d n=%d elapsed=%.1fs%n"
            + "LOOPBENCH_GC work_us p50=%.3f p95=%.3f p99=%.3f max=%.3f%n"
            + "LOOPBENCH_GC wake_ms p50=%.4f p95=%.4f p99=%.4f p999=%.4f max=%.4f%n"
            + "LOOPBENCH_GC overruns >1ms=%d >5ms=%d >2xperiod=%d"
            + " warmup_max=%.3fms steady_max=%.3fms%n"
            + "LOOPBENCH_GC worst=%s%n",
        m_label,
        m_period,
        m_prioAfter,
        m_count,
        elapsed,
        pct(work, 500) / 1000.0,
        pct(work, 950) / 1000.0,
        pct(work, 990) / 1000.0,
        work[m_count - 1] / 1000.0,
        pct(wake, 500) / 1e6,
        pct(wake, 950) / 1e6,
        pct(wake, 990) / 1e6,
        pct(wake, 999) / 1e6,
        wake[m_count - 1] / 1e6,
        over1ms,
        over5ms,
        overDouble,
        warmupMax / 1e6,
        steadyMax / 1e6,
        worst);

    if (m_enableSample >= 0) {
      StringBuilder around = new StringBuilder();
      for (int i = Math.max(0, m_enableSample - 2);
          i < Math.min(m_count, m_enableSample + 4);
          i++) {
        around.append(String.format("[%d]%.2fms ", i - m_enableSample, indexed[i] / 1e6));
      }
      System.out.printf(
          "LOOPBENCH_GC enable at_sample=%d t=%.1fs cost=%.3fms samples=%d traj=%s around %s%n",
          m_enableSample,
          m_enableSample * m_period,
          m_enableCostNanos / 1e6,
          m_traj == null ? -1 : m_traj.getSamples().size(),
          m_firstOutput == null ? "null" : "ok",
          around.toString().trim());
    }

    StringBuilder gc = new StringBuilder();
    for (Map.Entry<String, long[]> e : gcCounters().entrySet()) {
      long[] before = m_gcAtStart.getOrDefault(e.getKey(), new long[] {0, 0});
      gc.append(
          String.format(
              "%s(count=%d,time=%dms) ",
              e.getKey(), e.getValue()[0] - before[0], e.getValue()[1] - before[1]));
    }
    System.out.printf("LOOPBENCH_GC gc %s%n", gc.toString().trim());
    System.out.printf(
        "LOOPBENCH_GC alloc total=%.1fMB rate=%.2fMB/s per_loop=%.0fB match150s=%.0fMB%n",
        allocated / 1048576.0,
        allocated / elapsed / 1048576.0,
        (double) allocated / m_count,
        allocated / elapsed * 150 / 1048576.0);
    var after = allocPerThread();
    var deltas = new ArrayList<Map.Entry<String, Long>>();
    for (var e : after.entrySet()) {
      long d = e.getValue() - m_allocPerThreadAtStart.getOrDefault(e.getKey(), 0L);
      if (d > 0) {
        deltas.add(Map.entry(e.getKey(), d));
      }
    }
    deltas.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
    StringBuilder threads = new StringBuilder();
    for (int i = 0; i < Math.min(6, deltas.size()); i++) {
      threads.append(
          String.format(
              "%s=%.1fMB ", deltas.get(i).getKey(), deltas.get(i).getValue() / 1048576.0));
    }
    System.out.printf("LOOPBENCH_GC alloc_by_thread %s%n", threads.toString().trim());
    m_writer.flush();
    System.out.printf("LOOPBENCH_GC wpilog_bytes=%d%n", logBytes());
    System.out.println("LOOPBENCH_GC DONE");
    System.out.flush();
    if (readDouble("loopbench.exit", 0) > 0) {
      System.exit(0);
    }
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

  /** Permille percentile, so p999 is expressible. */
  private static long pct(long[] sorted, int permille) {
    return sorted[Math.min(sorted.length - 1, (int) ((long) permille * sorted.length / 1000))];
  }
}
