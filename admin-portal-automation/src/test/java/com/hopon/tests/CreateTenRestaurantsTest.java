package com.hopon.tests;

import org.testng.annotations.Test;

/** Runs the whole create-restaurant flow 10 times in a row. Each run creates a new restaurant. */
public class CreateTenRestaurantsTest extends CreateRestaurantTest {

    @Override
    @Test(invocationCount = 10)
    public void createRestaurant() {
        super.createRestaurant();
    }
}