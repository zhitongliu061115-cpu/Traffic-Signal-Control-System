from __future__ import annotations

import math
import re


def utm_to_wgs84(easting: float, northing: float, zone: int, northern: bool = True) -> tuple[float, float]:
    a = 6378137.0
    ecc_squared = 0.00669438
    k0 = 0.9996
    x = easting - 500000.0
    y = northing if northern else northing - 10000000.0
    ecc_prime_squared = ecc_squared / (1.0 - ecc_squared)
    m = y / k0
    mu = m / (a * (1.0 - ecc_squared / 4.0 - 3.0 * ecc_squared ** 2 / 64.0 - 5.0 * ecc_squared ** 3 / 256.0))
    e1 = (1.0 - math.sqrt(1.0 - ecc_squared)) / (1.0 + math.sqrt(1.0 - ecc_squared))
    phi1 = (
        mu
        + (3.0 * e1 / 2.0 - 27.0 * e1 ** 3 / 32.0) * math.sin(2.0 * mu)
        + (21.0 * e1 ** 2 / 16.0 - 55.0 * e1 ** 4 / 32.0) * math.sin(4.0 * mu)
        + 151.0 * e1 ** 3 / 96.0 * math.sin(6.0 * mu)
    )
    n1 = a / math.sqrt(1.0 - ecc_squared * math.sin(phi1) ** 2)
    t1 = math.tan(phi1) ** 2
    c1 = ecc_prime_squared * math.cos(phi1) ** 2
    r1 = a * (1.0 - ecc_squared) / (1.0 - ecc_squared * math.sin(phi1) ** 2) ** 1.5
    d = x / (n1 * k0)
    latitude = phi1 - (n1 * math.tan(phi1) / r1) * (
        d ** 2 / 2.0
        - (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 ** 2 - 9.0 * ecc_prime_squared) * d ** 4 / 24.0
        + (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 ** 2 - 252.0 * ecc_prime_squared - 3.0 * c1 ** 2) * d ** 6 / 720.0
    )
    longitude_origin = (zone - 1) * 6 - 180 + 3
    longitude = (
        d
        - (1.0 + 2.0 * t1 + c1) * d ** 3 / 6.0
        + (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 ** 2 + 8.0 * ecc_prime_squared + 24.0 * t1 ** 2) * d ** 5 / 120.0
    ) / math.cos(phi1)
    return longitude_origin + math.degrees(longitude), math.degrees(latitude)


def parse_utm_projection(projection: str) -> tuple[int, bool]:
    match = re.search(r"\+zone=(\d+)", projection)
    if match is None:
        raise ValueError(f"unsupported SUMO projection: {projection}")
    return int(match.group(1)), "+south" not in projection


def wgs84_to_gcj02(longitude: float, latitude: float) -> tuple[float, float]:
    if not (72.004 <= longitude <= 137.8347 and 0.8293 <= latitude <= 55.8271):
        return longitude, latitude
    d_lat = _transform_lat(longitude - 105.0, latitude - 35.0)
    d_lng = _transform_lng(longitude - 105.0, latitude - 35.0)
    rad_lat = math.radians(latitude)
    magic = math.sin(rad_lat)
    magic = 1.0 - 0.00669342162296594323 * magic * magic
    sqrt_magic = math.sqrt(magic)
    d_lat = d_lat * 180.0 / ((6378245.0 * (1.0 - 0.00669342162296594323)) / (magic * sqrt_magic) * math.pi)
    d_lng = d_lng * 180.0 / (6378245.0 / sqrt_magic * math.cos(rad_lat) * math.pi)
    return longitude + d_lng, latitude + d_lat


def _transform_lat(x: float, y: float) -> float:
    value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    value += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    value += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    value += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return value


def _transform_lng(x: float, y: float) -> float:
    value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    value += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    value += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    value += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return value
