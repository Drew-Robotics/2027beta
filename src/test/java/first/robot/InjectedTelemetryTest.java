// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.wpilib.units.Units.MetersPerSecond;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.internal.UnitTelemetry;
import org.wpilib.telemetry.MockTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.Measure;
import org.wpilib.units.measure.LinearVelocity;

class InjectedTelemetryTest {
  private MockTelemetryBackend backend;
  private TelemetryTable root;

  @BeforeEach
  void buildBackend() {
    // RobotBase's constructor is what registers this handler, and no Tier 1 test builds one.
    // Without it a Measure lands as its toString() and the unit property is never set.
    TelemetryRegistry.registerTypeHandler(
        Measure.class, (table, name, value) -> UnitTelemetry.log(table, name, value));
    backend = new MockTelemetryBackend();
    root = new TelemetryTable(backend);
  }

  @AfterEach
  void clearRegistry() {
    TelemetryRegistry.reset();
    backend.close();
  }

  @Test
  void aSignalWrittenThroughAnInjectedTableIsReadableByName() {
    var module = new Reporter(root.getTable("Drive").getTable("FrontLeft"));

    module.report(MetersPerSecond.of(3.25));

    assertEquals(3.25, backend.getLastValue("/Drive/FrontLeft/Velocity", Double.class));
  }

  // Stands in for a mechanism: it is handed its table rather than reaching for a global one,
  // which is the whole of what these assertions are about.
  private static final class Reporter {
    private final TelemetryTable log;

    Reporter(TelemetryTable log) {
      this.log = log;
    }

    void report(LinearVelocity velocity) {
      log.log("Velocity", velocity);
    }
  }
}
