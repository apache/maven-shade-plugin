/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.security.MessageDigest
import java.util.jar.JarFile

def originalChecksumsMD5 = [
    'ArrayUtils.class'                                    : '4644ce91f5dbf1172f3bc2eafa1220bc',
    'BitField.class'                                      : 'ec5d367938cb9f59912fafbe708112fa',
    'BooleanUtils.class'                                  : 'c321cc00995b4e81162cec9366406817',
    'builder/CompareToBuilder.class'                      : '70c085203ca3506fb0fbd1eed8de9ffe',
    'builder/EqualsBuilder.class'                         : 'e538ada29bf74d053b4174c6d0338c29',
    'builder/HashCodeBuilder.class'                       : '76e129cfdadefc856588351c830570e6',
    'builder/IDKey.class'                                 : 'fa08336106e492b5ac9f9901bd7ec690',
    'builder/ReflectionToStringBuilder.class'             : '5a30e353b9f1bd0c9d8ed547c3ef576d',
    'builder/StandardToStringStyle.class'                 : 'bd26865d3f4f0e3a0f24efe00897f4f3',
    'builder/ToStringBuilder.class'                       : '8493114752dd3552e21eba63343e6558',
    'builder/ToStringStyle$DefaultToStringStyle.class'    : 'e4d42ea42ea43ad971fe5d33624063a6',
    'builder/ToStringStyle$MultiLineToStringStyle.class'  : '3f2306c0f664229419dad8bfcfb0142d',
    'builder/ToStringStyle$NoFieldNameToStringStyle.class': '9ed4e831c533c0fc98f3583c1e6fda31',
    'builder/ToStringStyle$ShortPrefixToStringStyle.class': '4aa105e83bcd37a06f6d598a9c59d91c',
    'builder/ToStringStyle$SimpleToStringStyle.class'     : 'c156f9816faa3da7b664df81fe811b50',
    'builder/ToStringStyle.class'                         : '4088eef1b6a33ff2b6483b2f79461364',
    'CharEncoding.class'                                  : '68b058ceea276d04247aff21ef7dbb63',
    'CharRange$1.class'                                   : 'b3034e9540791bc9ce371e75d0ba8785',
    'CharRange$CharacterIterator.class'                   : 'fdb6df8bbf32f88f88749ade8552d081',
    'CharRange.class'                                     : 'a6b95e2ed34c8e46a9889568e56bacb8',
    'CharSet.class'                                       : '4e0df369374e877afefc76153797cffd',
    'CharSetUtils.class'                                  : '94a3d9c03ce37f671e5bdbb6605fe18b',
    'CharUtils.class'                                     : '5706b8e0802f818efac9e5c6c203d428',
    'ClassUtils.class'                                    : '09bc083bfe4f11d562d32c14b88b5a8e',
    'Entities$ArrayEntityMap.class'                       : 'ff3a4211ef9b3c463eb79c1d04ad8d25',
    'Entities$BinaryEntityMap.class'                      : '12b7fdc0621bea444f687950466fd89b',
    'Entities$EntityMap.class'                            : '8e35cd73a7f6d80f45b328c228bcbc7d',
    'Entities$HashEntityMap.class'                        : 'e8ab521f1a3fdca02c7e44daa33f7688',
    'Entities$LookupEntityMap.class'                      : '1e59392ae388c8da917881f9f8b4998d',
    'Entities$MapIntMap.class'                            : 'ce32c8d2649bbf479e7c511d110bf9c2',
    'Entities$PrimitiveEntityMap.class'                   : '110a8762a727788dc038d47385a55eaf',
    'Entities$TreeEntityMap.class'                        : '2a51fb240cd9cc51dbf249ebc20c5639',
    'Entities.class'                                      : '8d0f106f7591b55d0b57927d0fcce871',
    'enum/Enum$Entry.class'                               : 'bb9c0b1ecfcd16e873d180ce20129e9e',
    'enum/Enum.class'                                     : 'fda3012c2bc97b7d0425057b8d98ca30',
    'enum/EnumUtils.class'                                : 'd9d966cd69bef5a14d9c514a3aec8a1d',
    'enum/ValuedEnum.class'                               : '6196dd12bd0b6debcef93f78cae0f094',
    'enums/Enum$Entry.class'                              : 'a625be33cd61f7e00da41d868ac5fbde',
    'enums/Enum.class'                                    : '5769c6367611bdeaab3536c979e931ec',
    'enums/EnumUtils.class'                               : '5c4c227735393bcd5d550172afe0d1e7',
    'enums/ValuedEnum.class'                              : '7cc89eafa3f01eca7f989e4a95880484',
    'exception/CloneFailedException.class'                : 'a1d1fe5140c39f1d9c13b9716df320e0',
    'exception/ExceptionUtils.class'                      : 'bfbbc6b82e778a91c9ac885a582aa87a',
    'exception/Nestable.class'                            : 'cb37cc4f6aa95ddc1e08e24dfdb1faeb',
    'exception/NestableDelegate.class'                    : '72610ad6e1acd693a73bd169c4c2fe66',
    'exception/NestableError.class'                       : 'e9dec41924015b2a37fcc724994601d7',
    'exception/NestableException.class'                   : 'f88cb396c859f785b13272236dd2d14a',
    'exception/NestableRuntimeException.class'            : 'b3712109e98e7dfe916c028944be71a2',
    'IllegalClassException.class'                         : '592efb0cd4c69c78890f51e5aefd5323',
    'IncompleteArgumentException.class'                   : 'c65def57686f1433fec9de1a53d7e968',
    'IntHashMap$Entry.class'                              : '312431259a720c5cc59919164e1f6f94',
    'IntHashMap.class'                                    : '09e0c9e37e06edc13912e9179d123df2',
    'LocaleUtils.class'                                   : 'ba8b698331343045e4714ab276d84df3',
    'math/DoubleRange.class'                              : 'ae74dc56efa1d6608a34cd87326775df',
    'math/FloatRange.class'                               : 'ca15b57ff629ad21c135daa3af4ae748',
    'math/Fraction.class'                                 : '966d7dba648d56d40415f1d76eb61f22',
    'math/IEEE754rUtils.class'                            : '904f0342faa83dc32aa3ce09b570d6e6',
    'math/IntRange.class'                                 : '1a08884805a90e85a4af5f38f5c1d3ae',
    'math/JVMRandom.class'                                : '1c38d4c2b8dcd9feee7ca4affc945ef9',
    'math/LongRange.class'                                : 'ebc6dbf88a701e592764d635234eeec4',
    'math/NumberRange.class'                              : '78de0d669bc79bba406be19c5fdd0abb',
    'math/NumberUtils.class'                              : '5ae54b27c20fa65144d64c65449aaa89',
    'math/RandomUtils.class'                              : '40155ad57c6f32460e0f32f3db22b173',
    'math/Range.class'                                    : 'dab8235e8a5cc7d9b9103f416478f39a',
    'mutable/Mutable.class'                               : '0c3515bc904e276c781486c67ec34e71',
    'mutable/MutableBoolean.class'                        : '1f5979176185bf9638ca60d15526b45e',
    'mutable/MutableByte.class'                           : 'd458d842df3c178069a7969fc9f6af38',
    'mutable/MutableDouble.class'                         : '1c32ae0b6cdfc00d48280164a6ef8e9b',
    'mutable/MutableFloat.class'                          : 'f5a58dc62a9f95081f678db598f9513d',
    'mutable/MutableInt.class'                            : '2350cc67b99f6427d404ea7233d94664',
    'mutable/MutableLong.class'                           : '7459b453c34cfe6beeb877bcc5fc4e81',
    'mutable/MutableObject.class'                         : 'f16ed36d34163fd6219c59fffdc02760',
    'mutable/MutableShort.class'                          : 'e3192bee99861e409c941bc2a5be9add',
    'NotImplementedException.class'                       : '45f4bfa15f637b7ac8fba0ea5af9a981',
    'NullArgumentException.class'                         : '206902af1b00283b40b258b28385f63b',
    'NumberRange.class'                                   : '54fbe10934f95117f8d3f0c0546b56c7',
    'NumberUtils.class'                                   : '2de64e30b07a36e11f095f3146f16aa0',
    'ObjectUtils$Null.class'                              : '1138f88d2527f63458eaa56c959f818c',
    'ObjectUtils.class'                                   : '40483af3c9a0cdcb5ee0df43c1fa3ae8',
    'RandomStringUtils.class'                             : '1a4f147bab572b579db352d06979ce0a',
    'reflect/ConstructorUtils.class'                      : '96b06ea51ec96064b7d881975dffc8bb',
    'reflect/FieldUtils.class'                            : 'f37be95c51f707ea1dd965d02f20f540',
    'reflect/MemberUtils.class'                           : '9fe7b0a7c664458058668c5400a2139b',
    'reflect/MethodUtils.class'                           : '4d7781bf651d8a1f750ba75f628f45e4',
    'SerializationException.class'                        : '7eaeab8f24eeed40e089d4abeffc3ad4',
    'SerializationUtils.class'                            : '32062839391b9896caa63067a4040a6c',
    'StringEscapeUtils.class'                             : 'c1fe975a05f7d14823d3dbce29c23bb2',
    'StringUtils.class'                                   : 'bb4c964dd2c5057d3a8f282ea381c05d',
    'SystemUtils.class'                                   : '74492426bb3b60ea1e3c3816b955f40f',
    'text/CompositeFormat.class'                          : 'dd2b0fc2a4784eb07754f8271d758ba5',
    'text/ExtendedMessageFormat.class'                    : 'eb32c6eb2f83e784c5479a00edb9d35d',
    'text/FormatFactory.class'                            : 'b8b2a7245d750929eebf82c59a34af4a',
    'text/StrBuilder$StrBuilderReader.class'              : 'b67a32739b45226ae25297150f4a3672',
    'text/StrBuilder$StrBuilderTokenizer.class'           : '22d8351ad6688698c281e980b5e2aef0',
    'text/StrBuilder$StrBuilderWriter.class'              : '911b13b209c459ab0e649fcf16da119f',
    'text/StrBuilder.class'                               : '0daf9125d2bb85d7be8f0930d65a8881',
    'text/StrLookup$MapStrLookup.class'                   : '9468c0e1dbf42c9842f57662ab65a295',
    'text/StrLookup.class'                                : '5755445f8e6509993c501450b4ca0283',
    'text/StrMatcher$CharMatcher.class'                   : 'c40179bba463256255193200fad920b1',
    'text/StrMatcher$CharSetMatcher.class'                : '93b22e1f09237f052c32ec202d95dd16',
    'text/StrMatcher$NoMatcher.class'                     : '1bf4cce48a7fc1abd12ce0ddc32a0f21',
    'text/StrMatcher$StringMatcher.class'                 : 'e223633d564e39846dd74ce6aa343a1d',
    'text/StrMatcher$TrimMatcher.class'                   : '681a4bd78f7f2c5fd74a99370ad4b20b',
    'text/StrMatcher.class'                               : 'f5a6d712ffd750ca1c7613b394ea3f22',
    'text/StrSubstitutor.class'                           : '1cfa5ab4e0990595dec646037c5967a9',
    'text/StrTokenizer.class'                             : 'bf73deb873f50e8ba34de3998e2972c4',
    'time/DateFormatUtils.class'                          : 'fb537b3f1a510ac0c52804e4aff27763',
    'time/DateUtils$DateIterator.class'                   : 'ba97d5b0027fe3e5a591f07864e44bbb',
    'time/DateUtils.class'                                : 'df75657605e945dbbcbe886704470137',
    'time/DurationFormatUtils$Token.class'                : 'ed64a1f1a2a41e56ae50309a1fde175c',
    'time/DurationFormatUtils.class'                      : 'e393be23cc1ab1ff59091a1b83cc9bbd',
    'time/FastDateFormat$CharacterLiteral.class'          : '249c346d9821e022bdb659c222dbf644',
    'time/FastDateFormat$NumberRule.class'                : 'c41df9bc3bdff9cb4d68dd74885616b6',
    'time/FastDateFormat$PaddedNumberField.class'         : '4cd9e98b2afc00b17d0d2038b397de24',
    'time/FastDateFormat$Pair.class'                      : '8a934ec5d93793facff76c7e8cf3280e',
    'time/FastDateFormat$Rule.class'                      : '442ea43c86a34ef4a5d416d3dead1ada',
    'time/FastDateFormat$StringLiteral.class'             : '4d61134d7b4947e972418208f946417c',
    'time/FastDateFormat$TextField.class'                 : '6894c0815fcc85c5ceb9a22df07ab4fd',
    'time/FastDateFormat$TimeZoneDisplayKey.class'        : '20a24c7f131f38d1e4deb74572c10f45',
    'time/FastDateFormat$TimeZoneNameRule.class'          : '529cab8e6727bf6f67f921060d5be564',
    'time/FastDateFormat$TimeZoneNumberRule.class'        : '0217783a5cac5dc5ab9e32c7c1b82d63',
    'time/FastDateFormat$TwelveHourField.class'           : '17aa8c1efb59e32ab1c3309a315a2d4a',
    'time/FastDateFormat$TwentyFourHourField.class'       : 'a1a4a9dae07bb5f8406e4b21d36fe6a3',
    'time/FastDateFormat$TwoDigitMonthField.class'        : 'c5ace49716048e8f189fc1b24bad9a96',
    'time/FastDateFormat$TwoDigitNumberField.class'       : '92b23f96a47805d2f35df7436e268186',
    'time/FastDateFormat$TwoDigitYearField.class'         : '876f335aad5398564e7065a6dc049e35',
    'time/FastDateFormat$UnpaddedMonthField.class'        : '4471d8973417c87437795a7c14f6e1d9',
    'time/FastDateFormat$UnpaddedNumberField.class'       : '26bc03b8208bb6e1f5d815029165fde9',
    'time/FastDateFormat.class'                           : '81458555c631fd1cd7d1c7b580a252d0',
    'time/StopWatch.class'                                : 'c9cd6ccf4eb13950ce32bccc4229d3d2',
    'UnhandledException.class'                            : '01110b3515eb3f010cbc0485f1cb9b02',
    'Validate.class'                                      : 'ee955facbc21ae85ba81f2ca10c2dbb9',
    'WordUtils.class'                                     : '771ffae0f501e8378cda1a516bda46b2',
]

def jarFile = new JarFile( new File( basedir, "target/mshade-391-1.0.jar" ) )
try
{
    originalChecksumsMD5.each { checksumEntry ->
        def entryPath = "org/apache/commons/lang/$checksumEntry.key"
        def bytes = jarFile.getInputStream(jarFile.getJarEntry(entryPath)).bytes
        def jarEntryMD5 = MessageDigest.getInstance("MD5").digest(bytes).encodeHex().toString()
        assert checksumEntry.value == jarEntryMD5
    }
    true
}
finally
{
    jarFile.close()
}
