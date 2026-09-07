/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

class InfoS3CommandIT extends AbstractS3CommandIT implements InfoCommandContract {

    @Override
    public String plainFile() {
        return S3_FILE;
    }

    @Override
    public String nonexistentFile() {
        return S3_NONEXISTENT_FILE;
    }

    @Override
    public String kvMetadataFile() {
        return S3_KV_METADATA_FILE;
    }
}
