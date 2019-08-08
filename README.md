# tomcat-one-to-zero

1 tomcat 基于 ant 构建，如果想转为 maven，可以将源码下载到本地，然后新建 maven 项目； 

2 直接将 tomcat 的两个核心包 javax 和 org.apache 拷贝过来，然后进行编译，肯定会报一堆错； 

3 根据错误提示将依赖全部以 maven 方式导入；  

4 导入后重新编译，通过  

依赖导入以下包即可（tomcat-8.x版本）
```xml
    <!-- https://mvnrepository.com/artifact/org.apache.ant/ant -->
    <dependency>
      <groupId>org.apache.ant</groupId>
      <artifactId>ant</artifactId>
      <version>1.10.5</version>
    </dependency>

    <!-- https://mvnrepository.com/artifact/org.apache.tomcat.embed/tomcat-embed-jasper -->
    <dependency>
      <groupId>org.apache.tomcat.embed</groupId>
      <artifactId>tomcat-embed-jasper</artifactId>
      <version>8.5.43</version>
    </dependency>

    <!-- https://mvnrepository.com/artifact/wsdl4j/wsdl4j -->
    <dependency>
      <groupId>wsdl4j</groupId>
      <artifactId>wsdl4j</artifactId>
      <version>1.6.3</version>
    </dependency>

    <!-- https://mvnrepository.com/artifact/javax.xml/jaxrpc-api -->
    <dependency>
      <groupId>javax.xml</groupId>
      <artifactId>jaxrpc-api</artifactId>
      <version>1.1</version>
    </dependency>

    <!-- https://mvnrepository.com/artifact/org.eclipse.jdt/core -->
    <dependency>
      <groupId>org.eclipse.jdt</groupId>
      <artifactId>core</artifactId>
      <version>3.3.0-v_771</version>
    </dependency>
```
