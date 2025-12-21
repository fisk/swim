Strange Variety Vi Improved, or **swim**, is the ultimate text editor, written in Java.
Keybindings are similar to vi based editors. But this one is written in Java and runs in a JVM.

# Building #

Swim is using JDK 25, so make sure you have that installed.
In order to build swim, run the following command:

```
mvn clean package
```

# Running #

In order to edit a file with swim, use the following command:

```
java -XX:+UseZGC -cp "target/swim-0.0.1-SNAPSHOT.jar:target/libs/*" org.fisk.swim.Swim <file>
```

For the best experience, create an alias, like this:

```
alias swim="java -XX:+UseZGC -cp "<swim_path>/target/swim-0.0.1-SNAPSHOT.jar:<swim_path>target/libs/*" org.fisk.swim.Swim "
```

where `<swim_path>` is the path where swim was cloned. With this alias, you can open files like a pro:

```
swim <file>
```