package com.bencodez.simpleapi.tests.file;

import java.util.Arrays;
import java.util.List;

import com.bencodez.simpleapi.file.annotation.ConfigDataBoolean;
import com.bencodez.simpleapi.file.annotation.ConfigDataConfigurationSection;
import com.bencodez.simpleapi.file.annotation.ConfigDataDouble;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataListInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataListString;
import com.bencodez.simpleapi.file.annotation.ConfigDataString;

public class SampleConfig {

	@ConfigDataBoolean(path = "feature.enabled", defaultValue = true)
	private boolean featureEnabled;

	@ConfigDataConfigurationSection(path = "database.settings")
	private Object databaseSettings;

	@ConfigDataDouble(path = "threshold.limit", defaultValue = 0.75)
	private double thresholdLimit;

	@ConfigDataInt(path = "max.connections", defaultValue = 10)
	private int maxConnections;

	@ConfigDataListString(path = "user.roles")
	private List<String> userRoles;

	@ConfigDataString(path = "welcome.message", defaultValue = "Welcome!")
	private String welcomeMessage;
	
    @ConfigDataListInt(path = "numbers")
    private List<Integer> numbers = Arrays.asList(1, 2, 3);
}
