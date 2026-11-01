package com.hopon.utils;

/** Data for one category. The name is unique through the number. */
public record CategoryData(String nameEn, String nameBn, String description) {

    public static CategoryData forNumber(int n) {
        return new CategoryData(
                "Automated Food Burger " + n,
                "\u09A8\u09BE\u09B8\u09CD\u09A4\u09BE " + n,      // the Bangla word for breakfast, then the number
                "an item that was created/added automatically during an automated test run");
    }
}