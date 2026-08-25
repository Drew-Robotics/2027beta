// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "wpi/framework/OpModeRobot.hpp"
#include "wpi/math/estimator/SwerveDrivePoseEstimator3d.hpp"
#include "wpi/math/geometry/Pose3d.hpp"
#include "wpi/math/geometry/Rotation2d.hpp"
#include "wpi/math/geometry/Rotation3d.hpp"
#include "wpi/math/geometry/Translation2d.hpp"
#include "wpi/math/kinematics/ChassisVelocities.hpp"
#include "wpi/math/kinematics/SwerveDriveKinematics.hpp"
#include "wpi/math/kinematics/SwerveModulePosition.hpp"
#include "wpi/math/kinematics/SwerveModuleVelocity.hpp"
#include "wpi/system/Threads.hpp"

namespace {
constexpr int kSamples = 5000;
constexpr double kTrack = 0.29;
constexpr double kMaxSpeed = 4.5;

wpi::units::second_t ReadPeriod() {
  std::ifstream f{"/home/systemcore/loopbench.period"};
  double v = 0.001;
  if (f >> v && v > 0) {
    return wpi::units::second_t{v};
  }
  return wpi::units::second_t{0.001};
}

int ReadPriority() {
  std::ifstream f{"/home/systemcore/loopbench.priority"};
  int v = 0;
  if (f >> v) {
    return v;
  }
  return 0;
}

long Pct(std::vector<long>& sorted, int p) {
  return sorted[std::min<size_t>(sorted.size() - 1,
                                 static_cast<size_t>(p) * sorted.size() / 100)];
}
}  // namespace

class Robot : public wpi::OpModeRobot<Robot> {
 public:
  Robot() : wpi::OpModeRobot<Robot>{ReadPeriod()}, m_period{ReadPeriod()} {
    m_work.reserve(kSamples);
    m_wake.reserve(kSamples);
    std::printf("LOOPBENCHCPP period=%.4f s, requested priority=%d\n",
                m_period.value(), ReadPriority());
  }

  void RobotPeriodic() override {
    auto wake = std::chrono::steady_clock::now();

    if (!m_primed) {
      m_primed = true;
      m_before = wpi::GetCurrentThreadPriority();
      int want = ReadPriority();
      if (want > 0) {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
        m_setOk = wpi::SetCurrentThreadPriority(want);
#pragma GCC diagnostic pop
      }
      m_after = wpi::GetCurrentThreadPriority();
      std::printf("LOOPBENCHCPP priority before=%d requested=%d ok=%d after=%d\n",
                  m_before, want, m_setOk ? 1 : 0, m_after);
      std::fflush(stdout);
    }

    auto start = std::chrono::steady_clock::now();
    Workload();
    auto end = std::chrono::steady_clock::now();

    if (m_warmup < static_cast<int>(3.0 / m_period.value())) {
      m_warmup++;
      m_lastWake = wake;
      return;
    }

    if (m_work.size() < kSamples) {
      m_work.push_back(
          std::chrono::duration_cast<std::chrono::nanoseconds>(end - start)
              .count());
      m_wake.push_back(
          std::chrono::duration_cast<std::chrono::nanoseconds>(wake - m_lastWake)
              .count());
      m_lastWake = wake;
      if (m_work.size() == kSamples) {
        Report();
      }
    }
  }

 private:
  void Workload() {
    m_t += m_period.value();
    wpi::math::ChassisVelocities target{
        wpi::units::meters_per_second_t{3.0 * std::cos(m_t)},
        wpi::units::meters_per_second_t{3.0 * std::sin(m_t)},
        wpi::units::radians_per_second_t{4.0 * std::sin(m_t * 0.5)}};

    auto velocities = wpi::math::SwerveDriveKinematics<4>::DesaturateWheelVelocities(
        m_kinematics.ToSwerveModuleVelocities(target.Discretize(m_period)),
        wpi::units::meters_per_second_t{kMaxSpeed});

    for (int i = 0; i < 4; i++) {
      auto optimized = velocities[i].Optimize(m_positions[i].angle);
      m_positions[i].distance += optimized.velocity * m_period;
      m_positions[i].angle = optimized.angle;
    }

    m_estimator.Update(wpi::math::Rotation3d{0_rad, 0_rad,
                                             wpi::units::radian_t{m_t * 0.3}},
                       m_positions);
  }

  void Report() {
    auto work = m_work;
    auto wake = m_wake;
    std::sort(work.begin(), work.end());
    std::sort(wake.begin(), wake.end());
    std::printf(
        "LOOPBENCHCPP RESULT period=%.4f prio=%d n=%d work_us p50=%.3f p95=%.3f "
        "p99=%.3f max=%.3f | wake_ms p50=%.4f p95=%.4f p99=%.4f max=%.4f\n",
        m_period.value(), m_after, kSamples, Pct(work, 50) / 1000.0,
        Pct(work, 95) / 1000.0, Pct(work, 99) / 1000.0,
        work.back() / 1000.0, Pct(wake, 50) / 1e6, Pct(wake, 95) / 1e6,
        Pct(wake, 99) / 1e6, wake.back() / 1e6);
    std::fflush(stdout);
  }

  wpi::units::second_t m_period;
  wpi::math::SwerveDriveKinematics<4> m_kinematics{
      wpi::math::Translation2d{wpi::units::meter_t{kTrack},
                               wpi::units::meter_t{kTrack}},
      wpi::math::Translation2d{wpi::units::meter_t{kTrack},
                               wpi::units::meter_t{-kTrack}},
      wpi::math::Translation2d{wpi::units::meter_t{-kTrack},
                               wpi::units::meter_t{kTrack}},
      wpi::math::Translation2d{wpi::units::meter_t{-kTrack},
                               wpi::units::meter_t{-kTrack}}};
  wpi::util::array<wpi::math::SwerveModulePosition, 4> m_positions{
      wpi::util::empty_array};
  wpi::math::SwerveDrivePoseEstimator3d<4> m_estimator{
      m_kinematics, wpi::math::Rotation3d{}, m_positions, wpi::math::Pose3d{}};

  std::chrono::steady_clock::time_point m_lastWake{};
  std::vector<long> m_work;
  std::vector<long> m_wake;
  double m_t = 0;
  int m_warmup = 0;
  bool m_primed = false;
  bool m_setOk = false;
  int m_before = -1;
  int m_after = -1;
};

int main() {
  return wpi::StartRobot<Robot>();
}
