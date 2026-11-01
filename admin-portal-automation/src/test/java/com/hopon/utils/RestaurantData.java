package com.hopon.utils;

/** Unique test data for one restaurant. Everything is derived from n, except the password. */
public record RestaurantData(String name, String owner, String email, String phone, String password) {

    public static RestaurantData forNumber(int n) {
        return new RestaurantData(
                "Automated Restaurant " + n,
                "Abir Arshad Bot " + n,
                "automatedbot" + n + "@gmail.com",
                String.format("017%08d", 11111110L + n),   // n=1 -> 01711111111, n=2 -> 01711111112
                "123456");
    }
}