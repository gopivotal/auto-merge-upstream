/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestTemplate

@Grab(group='org.codehaus.groovy', module='groovy-json', version='2.1.7')
@Controller
class AutoMergeUpstream {

	def REPOSITORY_DIRECTORY = new File(System.getProperty('java.io.tmpdir'), 'repo')

	def logger = LoggerFactory.getLogger(this.getClass())

	def restTemplate = new RestTemplate()

	@Value('${DOWNSTREAM_URI}')
	def downstreamUri

	@Value('${UPSTREAM_URI}')
	def upstreamUri

	@Value('${FROM_ADDRESS}')
	def fromAddress

	@Value('${TO_ADDRESS}')
	def toAddress

	def hostname

	def username

	def password

	@Autowired
	AutoMergeUpstream(@Value('${VCAP_SERVICES}') String services) {
		def configuration =  new JsonSlurper().parseText(services)
			.find { it ==~ /^sendgrid.*$/ }.value[0]['credentials']

		hostname = configuration['hostname']
		username = configuration['username']
		password = configuration['password']
	}

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

		logger.info 'Auto-merge complete'
	}

	def cloneRepository() {
		logger.info "Cloning ${sterilizeUri(downstreamUri)}"

		"git clone ${downstreamUri} ${REPOSITORY_DIRECTORY}".execute().waitForProcessOutput()
		inRepository "git remote add upstream ${upstreamUri}"
	}

	def updateRemotes() {
		logger.info "Updating upstream/master"
		inRepository 'git fetch upstream master'

		logger.info "Updating origin/master"
		inRepository 'git fetch origin master'

		inRepository 'git reset --hard origin/master'
	}

	def attemptMerge() {
		logger.info "Attempting merge from upstream/master to origin/master"
		inRepository('git merge upstream/master').exitValue() == 0
	}

	def pushRepository() {
		logger.info "Pushing origin/master"
		inRepository 'git push origin master'
	}

	def	sendFailureEmail() {
		try {
		restTemplate.postForEntity('https://{hostname}/api/mail.send.json' +
									'?api_user={username}' +
									'&api_key={password}' +
									'&from={fromAddress}' +
									'&to={toAddress}' +
									'&subject={subject}' +
									'&text={content}',
									null, Map.class, [
										'hostname' : hostname,
										'username' : username,
										'password' : password,
										'fromAddress' : fromAddress,
										'toAddress' : toAddress,
										'subject' : 'Unable to merge upstream changes',
										'content' : "An attempt to merge from ${sterilizeUri(upstreamUri)} to ${sterilizeUri(downstreamUri)} has failed.  This merge must be execuated manually."
									])
		} catch (Exception e) {
			println e.getResponseBodyAsString()
		}
	}

	def sterilizeUri(s) {
		def uri = new URI(s)
		"${uri.scheme}://${uri.host}${uri.path}"
	}

	def inRepository(command) {
		def proc = command.execute(null, REPOSITORY_DIRECTORY)
		proc.waitForProcessOutput()

		proc
	}
}