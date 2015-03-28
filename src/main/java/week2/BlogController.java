/*
 * Copyright 2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package week2;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoDatabase;
import freemarker.template.Configuration;
import freemarker.template.SimpleHash;
import org.apache.commons.lang3.StringEscapeUtils;
import org.bson.Document;
import spark.ModelAndView;
import spark.Request;
import spark.template.freemarker.FreeMarkerEngine;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.util.HashMap;

import static spark.Spark.*;

/**
 * This class encapsulates the controllers for the blog web application.  It delegates all interaction with MongoDB to
 * three Data Access Objects (DAOs).
 * <p>
 * It is also the entry point into the web application.
 */
public class BlogController
{
	private final UserDAO userDAO;
	private final SessionDAO sessionDAO;

	public BlogController(String mongoURIString) throws IOException
	{
		final MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoURIString));
		final MongoDatabase blogDatabase = mongoClient.getDatabase("blog");

		userDAO = new UserDAO(blogDatabase);
		sessionDAO = new SessionDAO(blogDatabase);

		setPort(8082);
		initializeRoutes();
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length == 0)
		{
			new BlogController("mongodb://localhost");
		}
		else
		{
			new BlogController(args[0]);
		}
	}

	private void initializeRoutes() throws IOException
	{
		final Configuration configuration = new Configuration();
		configuration.setClassForTemplateLoading(BlogController.class, "/week2/freemarker");

		// this is the blog home page
		get("/", (request, response) -> {

			//String username = sessionDAO.findUserNameBySessionId(getSessionCookie(request));

			// this is where we would normally load up the blog data
			// but this week, we just display a placeholder.
			HashMap<String, String> root = new HashMap<>();

			return new ModelAndView(root, "blog_template.ftl");
		}, new FreeMarkerEngine(configuration));

		// present signup form for blog
		get("/signup", (request, response) -> {
			SimpleHash root = new SimpleHash();

			// initialize values for the form.
			root.put("username", "");
			root.put("password", "");
			root.put("email", "");
			root.put("password_error", "");
			root.put("username_error", "");
			root.put("email_error", "");
			root.put("verify_error", "");

			return new ModelAndView(root, "signup.ftl");
		}, new FreeMarkerEngine(configuration));

		// handle the signup post
		post("/signup", (request, response) -> {
			String email = request.queryParams("email");
			String username = request.queryParams("username");
			String password = request.queryParams("password");
			String verify = request.queryParams("verify");

			HashMap<String, String> root = new HashMap<>();
			root.put("username", StringEscapeUtils.escapeHtml4(username));
			root.put("email", StringEscapeUtils.escapeHtml4(email));

			if (validateSignup(username, password, verify, email, root))
			{
				// good user
				System.out.println("Signup: Creating user with: " + username + " " + password);
				if (!userDAO.addUser(username, password, email))
				{
					// duplicate user
					root.put("username_error", "Username already in use, Please choose another");
					return new ModelAndView(root, "signup.ftl");
				}
				else
				{
					// good user, let's start a session
					String sessionID = sessionDAO.startSession(username);
					System.out.println("Session ID is" + sessionID);

					response.raw().addCookie(new Cookie("session", sessionID));
					response.redirect("/welcome");
				}
			}
			else
			{
				// bad signup
				System.out.println("User Registration did not validate");
				return new ModelAndView(root, "signup.ftl");
			}
			return null;
		}, new FreeMarkerEngine(configuration));

		get("/welcome", (request, response) -> {
			String cookie = getSessionCookie(request);
			String username = sessionDAO.findUserNameBySessionId(cookie);

			if (username == null)
			{
				System.out.println("welcome() can't identify the user, redirecting to signup");
				response.redirect("/signup");

			}
			else
			{
				SimpleHash root = new SimpleHash();
				root.put("username", username);
				return new ModelAndView(root, "welcome.ftl");
			}
			return null;
		}, new FreeMarkerEngine(configuration));

		// present the login page
		get("/login", (request, response) -> {
			SimpleHash root = new SimpleHash();

			root.put("username", "");
			root.put("login_error", "");

			return new ModelAndView(root, "login.ftl");
		}, new FreeMarkerEngine(configuration));

		// process output coming from login form. On success redirect folks to the welcome page
		// on failure, just return an error and let them try again.
		post("/login", (request, response) -> {

			String username = request.queryParams("username");
			String password = request.queryParams("password");

			System.out.println("Login, User submitted: " + username + "  " + password);

			Document user = userDAO.validateLogin(username, password);

			if (user != null)
			{

				// valid user, let's log them in
				String sessionID = sessionDAO.startSession(user.get("_id").toString());

				if (sessionID == null)
				{
					response.redirect("/internal_error");
				}
				else
				{
					// set the cookie for the user's browser
					response.raw().addCookie(new Cookie("session", sessionID));
					response.redirect("/welcome");
				}
			}
			else
			{
				SimpleHash root = new SimpleHash();
				root.put("username", StringEscapeUtils.escapeHtml4(username));
				root.put("password", "");
				root.put("login_error", "Invalid Login");
				return new ModelAndView(root, "login.ftl");
			}
			return null;
		}, new FreeMarkerEngine(configuration));

		// allows the user to logout of the blog
		get("/logout", (request, response) -> {

			String sessionID = getSessionCookie(request);

			if (sessionID == null)
			{
				// no session to end
				response.redirect("/login");
			}
			else
			{
				// deletes from session table
				sessionDAO.endSession(sessionID);

				// this should delete the cookie
				Cookie c = getSessionCookieActual(request);
				c.setMaxAge(0);

				response.raw().addCookie(c);

				response.redirect("/login");
			}
			return null;
		}, new FreeMarkerEngine(configuration));


		// used to process internal errors
		get("/internal_error", (request, response) -> {
			SimpleHash root = new SimpleHash();
			root.put("error", "System has encountered an error.");
			return new ModelAndView(root, "error_template.ftl");
		}, new FreeMarkerEngine(configuration));

	}

	// helper function to get session cookie as string
	private String getSessionCookie(final Request request)
	{
		if (request.raw().getCookies() == null)
		{
			return null;
		}
		for (Cookie cookie : request.raw().getCookies())
		{
			if (cookie.getName().equals("session"))
			{
				return cookie.getValue();
			}
		}
		return null;
	}

	// helper function to get session cookie as string
	private Cookie getSessionCookieActual(final Request request)
	{
		if (request.raw().getCookies() == null)
		{
			return null;
		}
		for (Cookie cookie : request.raw().getCookies())
		{
			if (cookie.getName().equals("session"))
			{
				return cookie;
			}
		}
		return null;
	}

	// validates that the registration form has been filled out right and username conforms
	public boolean validateSignup(String username, String password, String verify, String email,
	                              HashMap<String, String> errors)
	{
		String USER_RE = "^[a-zA-Z0-9_-]{3,20}$";
		String PASS_RE = "^.{3,20}$";
		String EMAIL_RE = "^[\\S]+@[\\S]+\\.[\\S]+$";

		errors.put("username_error", "");
		errors.put("password_error", "");
		errors.put("verify_error", "");
		errors.put("email_error", "");

		if (!username.matches(USER_RE))
		{
			errors.put("username_error", "invalid username. try just letters and numbers");
			return false;
		}

		if (!password.matches(PASS_RE))
		{
			errors.put("password_error", "invalid password.");
			return false;
		}


		if (!password.equals(verify))
		{
			errors.put("verify_error", "password must match");
			return false;
		}

		if (!email.equals(""))
		{
			if (!email.matches(EMAIL_RE))
			{
				errors.put("email_error", "Invalid Email Address");
				return false;
			}
		}

		return true;
	}
}
