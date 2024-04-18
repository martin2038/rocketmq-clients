

## 上传指南

依赖内容
https://central.sonatype.org/publish/requirements/

* Supply Javadoc and Sources
* Provide Files Checksums 
  * .md5 and .sha1 are required,
  * .sha256 and .sha512 are supported but not mandatory.
* Sign Files with GPG/PGP
* Sufficient Metadata
* Correct Coordinates
  * The version can be an arbitrary string and `can not` end in `-SNAPSHOT`
* Project Name, Description and URL
* License / Developer / SCM  Information


https://central.sonatype.org/publish-ea/publish-ea-guide/

版本一旦发布成功，即不可改变，可通过 [ Semantic Versioning ](https://semver.org/)进行升级

## gpg

macos 上传失败，直接[网页版上传](https://keyserver.ubuntu.com)


```bash
% gpg --keyserver keyserver.ubuntu.com --send-keys D28CFCBA420A37FC108258250DE23D49C35E7F3F
gpg: sending key 0DE23D49C35E7F3F to hkp://keys.openpgp.org
gpg: keyserver send failed: Operation not supported
gpg: keyserver send failed: Operation not supported

$ gpg -a --export D28CFCBA420A37FC108258250DE23D49C35E7F3F                                        
-----BEGIN PGP PUBLIC KEY BLOCK-----

mDMEZh+qzBYJKwYBBAHaRw8BAQdANT58gtsh2kUtQnzfyCM9R8gFdJTL4+sPAqlN
s7eKzyO0Hm1hcnRpbjIwMzggPGN5eTJjeXlAZ21haWwuY29tPoiZBBMWCgBBFiEE
0oz8ukIKN/wQglglDeI9ScNefz8FAmYfqswCGwMFCQWjmoAFCwkIBwICIgIGFQoJ
CAsCBBYCAwECHgcCF4AACgkQDeI9ScNefz9hYQEAro3ZVTWDp239ooJ5n5GV2VpA
u3UP+BRpnoZj0tjL2fkBAMFzAXapzlZ52XkhyIXPKHSVIXs+Nag6bjzhhuCdxHoL
uDgEZh+qzBIKKwYBBAGXVQEFAQEHQGToBDrm/k9DEmlCkZBmKn6NB7FKGEAbLZof
oZWer0tQAwEIB4h+BBgWCgAmFiEE0oz8ukIKN/wQglglDeI9ScNefz8FAmYfqswC
GwwFCQWjmoAACgkQDeI9ScNefz/Q5AEA9s459iXqys8LDwJ74vIOyUamwds+y5r8
9wUuj0ILM6oBAL0YQHTgTPKGabwf6v2vgEomTW7YP7tQaZs0Cwe6hnYI
=k0q0
-----END PGP PUBLIC KEY BLOCK-----

```
上传成功后，返回

https://keyserver.ubuntu.com/pks/add
```json
{
  "inserted": [
    "eddsa263/d28cfcba420a37fc108258250de23d49c35e7f3f"
  ],
  "updated": null,
  "ignored": null
}
```

上传`keys.openpgp.org`
```bash
$ gpg --export D28CFCBA420A37FC108258250DE23D49C35E7F3F | curl -T - https://keys.openpgp.org
Key successfully uploaded. Proceed with verification here:
https://keys.openpgp.org/upload/QNH1BhLOBaWY4Mjtxxx
点击链接，发送邮件进行验证

https://keys.openpgp.org/vks/v1/by-fingerprint/D28CFCBA420A37FC108258250DE23D49C35E7F3F
```

导出私钥：`secretKeyRingFile`

https://docs.gradle.org/current/userguide/signing_plugin.html

gpg --keyring secring.gpg --export-secret-keys > ~/.gnupg/martin2038-secring.gpg


## 发布

### com.vanniktech.maven.publish 插件

https://vanniktech.github.io/gradle-maven-publish-plugin/#advantages-over-maven-publish

* 支持多项目
* 支持Maven central
* 支持In memory GPG signing， `CI`友好



https://central.sonatype.com/publishing/deployments

* 手动发布
```bash
# 可多次发布
gradle publishToMavenCentral  --no-configuration-cache
# 自动发布
gradle publishAndReleaseToMavenCentral --no-configuration-cache
```
去网站`Manual release`

