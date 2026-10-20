pluginManagement {
    repositories {
        val proxy=System.getenv("AGENTBRIDGE_MAVEN_PROXY")
        if(proxy!=null) { for(repo in listOf("google","central","plugins")) maven { url=uri("$proxy/$repo");isAllowInsecureProtocol=true
            if(repo=="google") content { includeGroupByRegex("com[.]android.*");includeGroupByRegex("androidx.*");includeGroupByRegex("android[.]arch.*");includeGroupByRegex("com[.]google[.]android.*");includeGroup("com.google.testing.platform") }
        } }
        else { google();mavenCentral();gradlePluginPortal() }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val proxy=System.getenv("AGENTBRIDGE_MAVEN_PROXY")
        if(proxy!=null) { for(repo in listOf("google","central")) maven { url=uri("$proxy/$repo");isAllowInsecureProtocol=true
            if(repo=="google") content { includeGroupByRegex("com[.]android.*");includeGroupByRegex("androidx.*");includeGroupByRegex("android[.]arch.*");includeGroupByRegex("com[.]google[.]android.*");includeGroup("com.google.testing.platform") }
        } }
        else { google();mavenCentral() }
    }
}

rootProject.name = "AndroidAgentBridge"
include(":app", ":bridgehooks")
