/**
 * Signal control strategy extension point.
 *
 * Keep strategy implementations behind TrafficSignalController so RL,
 * Max-Pressure, and FixedTime can be swapped without changing simulation APIs.
 */
package com.traffic.strategy;
