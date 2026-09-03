package com.world_dance.ms_music_media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.world_dance.ms_music_media.client")
@EnableMongoRepositories(basePackages = "com.world_dance.wd_lib_common.repository")
public class MsMusicMediaApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsMusicMediaApplication.class, args);
	}

}
