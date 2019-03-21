/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.sendgrid.SendGrid
import org.slf4j.LoggerFactory

@Grab('com.sendgrid:sendgrid-java:0.1.2')
@Grab('spring-boot-starter-actuator')
@Controller
class AutoMergeUpstream {

  def REPOSITORY_DIRECTORY = new File(System.getProperty('java.io.tmpdir'), 'repo')

  def logger = LoggerFactory.getLogger(this.getClass())

  @Value('${downstream.uri}')
  def downstreamUri

  @Value('${upstream.uri}')
  def upstreamUri

  @Value('${from.address}')
  def fromAddress

  @Value('${to.address}')
  def toAddress

  @Value('${vcap.services.sendgrid.credentials.username}')
  def username

  @Value('${vcap.services.sendgrid.credentials.password}')
  def password

  @RequestMapping(method = RequestMethod.POST, value = '/')
  ResponseEntity<Void> webhook() {
    Thread.start { merge() }
    return new ResponseEntity<>(HttpStatus.OK)
  }

  def merge() {
    if (!REPOSITORY_DIRECTORY.exists()) {
      cloneRepository()
    }

    updateRemotes()
    def mergeSuccessful = attemptMerge()

    if (mergeSuccessful) {
      pushRepository()
    } else {
      sendFailureEmail()
    }

    logger.info('Auto-merge complete')
  }

  def cloneRepository() {
    logger.info('Creating repository')

    REPOSITORY_DIRECTORY.mkdirs()

    inRepository(['git', 'init'])
    inRepository(['git', 'config', 'user.email', fromAddress])
    inRepository(['git', 'config', 'user.name', 'Auto Merge Upstream'])
    inRepository(['git', 'remote', 'add', 'upstream', upstreamUri])
    inRepository(['git', 'remote', 'add', 'downstream', downstreamUri])
  }

  def updateRemotes() {
    logger.info('Updating upstream')
    inRepository(['git', 'fetch', '-u', 'upstream'])

    logger.info('Updating downstream')
    inRepository(['git', 'fetch', '-u', 'downstream'])

    inRepository(['git', 'reset', '--hard', 'downstream/master'])
  }

  def attemptMerge() {
    logger.info('Attempting merge from upstream/master to downstream/master')
    inRepository(['git', 'merge', 'upstream/master']).exitValue() == 0
  }

  def pushRepository() {
    logger.info('Pushing downstream/master')
    inRepository(['git', 'push', 'downstream', 'master'])
  }

  def sendFailureEmail() {
    logger.info("Sending failure email to ${toAddress}")

    def sendGrid = new SendGrid(username, password)
    sendGrid.setFrom(fromAddress)
    sendGrid.addTo(toAddress)
    sendGrid.setSubject('Unable to merge upstream changes')
    sendGrid.setText("An attempt to merge from ${sterilizeUri(upstreamUri)} to ${sterilizeUri(downstreamUri)} has " +
                     'failed.  This merge must be executed manually.')
    sendGrid.send()
  }

  def sterilizeUri(s) {
    def uri = new URI(s)
    "${uri.scheme}://${uri.host}${uri.path}"
  }

  def inRepository(command) {
    def proc = command.execute(null, REPOSITORY_DIRECTORY)
    proc.waitFor()

    if (proc.exitValue() != 0) {
      logger.error("stdout: {}", proc.in.text)
      logger.error("stderr: {}", proc.err.text)
    }

    proc
  }
}
