// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctre.phoenix6.StatusCode;
import com.revrobotics.REVLibError;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.wpilib.util.AlertDataJNI;
import org.wpilib.util.AlertDataJNI.AlertInfo;

class HardwareTest {
  @Test
  void okOnTheFirstAttemptCallsOnceAndRaisesNothing() {
    var attempts = new AtomicInteger();

    Hardware.configureSpark("HardwareTestOkFirst", () -> returning(attempts, REVLibError.kOk));

    assertEquals(1, attempts.get());
    assertNull(alert("HardwareTestOkFirst"));
  }

  @Test
  void aTimeoutIsRetriedUntilItSucceeds() {
    var attempts = new AtomicInteger();

    Hardware.configureSpark(
        "HardwareTestTimeoutThenOk",
        () -> attempts.incrementAndGet() < 3 ? REVLibError.kTimeout : REVLibError.kOk);

    assertEquals(3, attempts.get());
    assertNull(alert("HardwareTestTimeoutThenOk"));
  }

  @Test
  void aPersistentTimeoutStopsAtFiveAttemptsAndAlerts() {
    var attempts = new AtomicInteger();

    Hardware.configureSpark(
        "HardwareTestAlwaysTimeout", () -> returning(attempts, REVLibError.kTimeout));

    assertEquals(5, attempts.get());
    assertActive("HardwareTestAlwaysTimeout");
  }

  // kOk is returned and everything but kTimeout and kCannotPersistParametersWhileEnabled is
  // thrown, so a returned error other than those two is the half of the failure surface a
  // try/catch alone never sees.
  @Test
  void aReturnedErrorThatIsNotATimeoutIsNeverRetried() {
    var attempts = new AtomicInteger();

    Hardware.configureSpark(
        "HardwareTestPersistWhileEnabled",
        () -> returning(attempts, REVLibError.kCannotPersistParametersWhileEnabled));

    assertEquals(1, attempts.get());
    var raised = assertActive("HardwareTestPersistWhileEnabled");
    assertTrue(
        raised.text.contains("kCannotPersistParametersWhileEnabled"),
        "the alert does not name the vendor error: " + raised.text);
  }

  @Test
  void aThrownFailureIsNeverRetried() {
    var attempts = new AtomicInteger();

    Hardware.configureSpark(
        "HardwareTestThrows",
        () -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("kInvalidCANId");
        });

    assertEquals(1, attempts.get());
    var raised = assertActive("HardwareTestThrows");
    assertTrue(
        raised.text.contains("kInvalidCANId"),
        "the alert does not carry the cause: " + raised.text);
  }

  @Test
  void oneDeadDeviceDoesNotStopTheOnesThatAreFine() {
    var second = new AtomicInteger();

    Hardware.configureSpark(
        "HardwareTestDead",
        () -> {
          throw new IllegalStateException("kCANDisconnected");
        });
    Hardware.configureSpark("HardwareTestAlive", () -> returning(second, REVLibError.kOk));

    assertEquals(1, second.get());
    assertActive("HardwareTestDead");
    assertNull(alert("HardwareTestAlive"));
  }

  @Test
  void phoenixRetriesANonOkStatusAndStopsAtFive() {
    var attempts = new AtomicInteger();

    Hardware.configurePhoenix(
        "HardwareTestPhoenixBad", () -> returning(attempts, StatusCode.TxTimeout));

    assertEquals(5, attempts.get());
    assertActive("HardwareTestPhoenixBad");
  }

  @Test
  void phoenixStopsAsSoonAsTheStatusIsOk() {
    var attempts = new AtomicInteger();

    Hardware.configurePhoenix(
        "HardwareTestPhoenixOk",
        () -> attempts.incrementAndGet() < 2 ? StatusCode.TxTimeout : StatusCode.OK);

    assertEquals(2, attempts.get());
    assertNull(alert("HardwareTestPhoenixOk"));
  }

  private static <T> T returning(AtomicInteger attempts, T status) {
    attempts.incrementAndGet();
    return status;
  }

  private static AlertInfo assertActive(String id) {
    var raised = alert(id);
    assertNotNull(raised, "no alert was raised for " + id + ", only " + ids());
    assertTrue(raised.activeStartTime != 0, "the alert for " + id + " was never set active");
    assertEquals(AlertDataJNI.LEVEL_HIGH, raised.level, "a failed device is not a HIGH alert");
    return raised;
  }

  private static AlertInfo alert(String id) {
    return Arrays.stream(AlertDataJNI.getAlerts())
        .filter(a -> a.id.equals(id))
        .findFirst()
        .orElse(null);
  }

  private static List<String> ids() {
    return Arrays.stream(AlertDataJNI.getAlerts()).map(a -> a.id).toList();
  }
}
