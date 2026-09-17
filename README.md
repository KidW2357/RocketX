
<p align="center">
  <a href="https://github.com/trycatchx/RocketXPlugin">
    <img width="200" src="https://github.com/trycatchx/RocketXPlugin/blob/master/rocketX-studio-plugin/resources/META-INF/pluginIcon.svg">
  </a>
</p>

<h1 align="center">RocketX</h1>
<div align="center">

本插件自动识别未改动 module 并在编译流程中替换为 aar ，做到只编译当前改动的 module，加速 Android apk 的编译速度。让你体验到所有模块都是 aar 的速度，又能保留所有的 module 便于修改，完美！（开源不易，希望朋友小手一抖，右上角来个star，感谢🙏）

</div>

<div align="center">

[English Document](https://github.com/trycatchx/RocketXPlugin/blob/master/README-EN.md)  | [Blog讲解](https://juejin.cn/post/7038157787976695815)

</div>

## dev_zy 定制分支

`dev_zy` 面向大型、多模块 Android 工程，目标运行环境为 Gradle 6.9.1、
Android Gradle Plugin 4.1.3 和 Kotlin 1.3.72。该分支在上游 AAR 缓存方案之上增加了以下保护：

* 默认保留所有 Transform；只有显式配置的 Transform 才会被禁用。
* 使用完整 project path 匹配依赖，支持不同目录下存在同名 module。
* 变更会沿反向依赖图传播，避免复用依赖了旧 API 或旧资源的 AAR。
* 缓存产物缺失时自动回退源码编译，构建失败时不更新 module 快照。
* module 指纹包含相对路径、文件大小和修改时间，并跟踪根构建配置。
* 支持通过 Gradle property 启用，无需安装 Android Studio 插件。
* 默认关闭 dex merge 增量复用，先保证多 Transform 工程的正确性。

该分支默认关闭。建议仅在本地 Debug assemble 中显式启用：

```bash
./gradlew :app:assembleDebug \
  -Procketx.enabled=true \
  --no-configuration-cache \
  --no-configure-on-demand
```

RocketX 会动态修改项目依赖，因此启用时不兼容 Gradle configuration cache 和
configuration on demand。检测到这两项开启时，插件会直接失败并输出修复提示。

> 注意：官方 `io.github.trycatchx:rocketx:1.1.1` 不包含 `dev_zy` 的修复。
> 在发布内部制品前，可将本仓库的 `buildSrc` 作为源码方式接入验证。

## 编译速度对比

![2788235-0f027965fefc94f7](https://user-images.githubusercontent.com/6050250/222663410-12d0ffcc-4b80-445f-98d0-472e2b7f05c6.png)

## AGP 版本兼容
Plugin version | Gradle version
---|---
4.0.0+ | 6.1.1+
4.1.0+ | 6.5+
4.2.0+ |6.7.1+
7.0    |7.0+

## 如何使用

* 依赖 gradle 插件

```
// app module 的 build.gradle 加入
apply plugin: 'com.rocketx'

// 在根目录的 build.gradle 加入
buildscript {
    dependencies {
        classpath 'io.github.trycatchx:rocketx:1.1.1'
    }
}
```


* 依赖 AS 插件 android studio setting->plugins-> marketplace 搜索 RocketX 安装

<img width="1117" alt="image" src="https://user-images.githubusercontent.com/6050250/222663819-b3ad8aa0-6eef-4d3d-9535-e375b3b2457c.png">


*  使用点击小火箭至喷火icon （开启 状态）,点击编译器 run 按钮 :
<img width="658" alt="image" src="https://user-images.githubusercontent.com/6050250/222664442-49621460-e3e3-412f-9fc5-789e3e169195.png">


######  如果你有多个 app module 也可选择 Assemble${flavor}${buildType} task 进行 run


## 配置（可选）
* openLog ：打开 log
* excludeModule :哪一些模块不需要打成 aar（譬如有些模块使用了 tool:replace="XX" ,打成 aar 后属性会消失，当然也可以移动到 app module 的 AndroidMenifest.xml）

```
  //app moodule下 配置插件编译项
  android {
  //..
    RocketX {
        openLog = true
        //指定哪些模块不打成 aar ，字符串为 module.path,以下 moduleB 不是一级目录，需要带上父文件夹
        excludeModule = [":moduleA",":module_common:moduleB"]
        // dev_zy 默认关闭；完成字节码链路验证后可改为 true 以获得更多收益
        dexMergeIncremental = false
        // 当前工程已配置这些选项，默认不要由插件修改 worker/kapt 等全局参数
        tuneGradleOptions = false
    }
   //..
   }
```
* `rocketx.excludeTransforms`：可选，仅禁用显式列出的 transform。`dev_zy` 不再默认禁用任何 transform。

```
# 使用空格间隔开；不要禁用应用运行所依赖的字节码处理
rocketx.excludeTransforms = example.transform.Name
```


## 问题
* 启用插件时必须将 `org.gradle.configuration-cache` 和 `org.gradle.configureondemand` 设置为 `false`。
* 第一次的加速，是最慢的因为需要全量编译后，打出 aar 上传到 LocalMaven
* 目前如果编译出错，请重新再 run 一次，出现的问题 欢迎提 issue



## 开发维护者
<table>
  <tr>
    <td align="center"><a href="https://github.com/trycatchx"><img src="https://avatars.githubusercontent.com/u/6050250?s=400&u=61b9ec2b9255ea464605a60fa810ceef80ccb740&v=4" style="width:100px; height:100px; border-radius:50%;"/><br /><sub><b>trycatchx</b><br /><b>(日落西来,月向东)</b></sub></a>
 </td> 
 <td align="center"><a href="https://github.com/JustAClamber"><img src="https://avatars.githubusercontent.com/u/18254533?v=4" style="width:100px; height:100px; border-radius:50%;"/><br /><sub><b>JustAClamber</b><br /><b>(知者不惑)</b></sub></a>
 </td>
  <td align="center"><a href="https://github.com/louis-lzt"><img src="https://avatars.githubusercontent.com/u/62166780?v=4" style="width:100px; height:100px; border-radius:50%;"/><br /><sub><b>louis</b><br /><b>(louis-lzt)</b></sub></a>
 </td>  
   <td align="center"><a href="https://github.com/FamilyCYZ"><img src="https://avatars.githubusercontent.com/u/37532300?v=4" style="width:100px; height:100px; border-radius:50%;"/><br /><sub><b>FamilyCYZ</b><br /><b>(什么都没有留下)</b></sub></a>
 </td> 
   <td align="center"><a href="https://github.com/quan229870530"><img src="https://avatars.githubusercontent.com/u/16531199?v=4" style="width:100px; height:100px; border-radius:50%;"/><br /><sub><b>quan229870530</b><br /><b>(什么都没有留下)</b></sub></a>
 </td> 
  </tr>
</table>

## 为爱发电（贡献者）

账号 | 留言
--- | ---
[XZQ](https://github.com/XZQ) | XZQ

## 为爱发电[文档](https://docs.qq.com/sheet/DVExXTENVRUtTdnBl?tab=BB08J2)
## 交流群
先加微信（备注 RocketX）再拉进群

<img width="388" alt="image" src="https://user-images.githubusercontent.com/6050250/157576321-518fea94-b7ac-4e8a-a864-fe6fbc44c300.png">



## License

```
Copyright (C) 2022 237939682@qq.com

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
